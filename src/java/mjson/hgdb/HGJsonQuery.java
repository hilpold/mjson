package mjson.hgdb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import org.hypergraphdb.HGHandle;
import org.hypergraphdb.HGQuery;
import org.hypergraphdb.HGRandomAccessResult;
import org.hypergraphdb.HGSearchResult;
import org.hypergraphdb.HGQuery.hg;
import org.hypergraphdb.query.And;
import org.hypergraphdb.query.impl.FilteredResultSet;
import org.hypergraphdb.query.impl.KeyBasedQuery;
import org.hypergraphdb.query.impl.PipedResult;
import org.hypergraphdb.util.HGUtils;
import org.hypergraphdb.util.Mapping;

import mjson.Json;
import mjson.hgdb.querying.CrossProductResultSet;

/**
 * <p>
 * Helper class to do pattern look in a JSON data set.
 * </p>
 * 
 * @author Borislav Iordanov
 *
 */
class HGJsonQuery
{
    static ThreadLocal<String> systemPrefix = new ThreadLocal<String>() {
        public String initialValue() { return "$"; }
    };
    
    static String sysPrefix()
    {
        return systemPrefix.get();
    }
    
    static String asSys(String propName) { return sysPrefix() + propName; }
    
    static double keywordScore(String text, String[] keywords)
    {
        StringTokenizer tokenizer = new StringTokenizer(text, " \t,.;?!~`@#$%^&*()-_+=\"[]{}:'<>/\\\n\r", false);
        int cnt = 0;
        int total = 0;
        while (tokenizer.hasMoreTokens())
        {
            for (int i = 0; i < keywords.length; i++)
                if (keywords[i].equalsIgnoreCase(tokenizer.nextToken()))
                    cnt++;
            total++;
        }
        return (double)cnt/(double)total;
    }
    
    /**
     * Predicate interface declared as a Json->Json mapping, returning its
     * argument when a certain condition is met and null otherwise.
     */
    static abstract class ItemMap implements Mapping<Json, Json> {}
    
    /**
     * Predicate checking that a given object property is a string matching a regular expression.
     */
    static class RegExFilter extends ItemMap 
    {
        String property;
        Pattern regex;
        
        public RegExFilter(String property, String regex) 
        {
            this.property = property;
            this.regex = Pattern.compile(regex); 
        }
            
        public Json eval(Json entity)
        {
            Json value = entity.at(property);
            if (!value.isString())
                return Json.nil();
            else if (regex.matcher(value.asString()).matches())
                return entity;
            else
                return Json.nil();                    
        }
    }
    
    /**
     * Predicate checking that a given properties is a string (a long text) where 
     * any of the defined keywords appear with a certain cummulative frequency (assignScore).
     */
    static class KeywordMatch extends ItemMap
    {
        String property;
        String [] keywords;
        boolean assignScore;
        
        public KeywordMatch(String property, String []keywords, boolean assignScore) 
        {
            this.property = property;
            this.keywords = keywords; 
            this.assignScore = assignScore;
        }
            
        public Json eval(Json entity)
        {
            Json value = entity.at(property);
            if (!value.isString())
                return Json.nil();
            double score = keywordScore(value.asString(), this.keywords);
            if (score == 0)
                return Json.nil();
            if (assignScore)
            {
                Json existing = entity.at(asSys("score"));
                if (existing == null)
                    entity.set(asSys("score"), score);
                else   
                    entity.set(asSys("score"), existing.asDouble() + score);
            }
            return entity;
        }
    }
    
    /**
     * Predicate returning its argument whenever it has one of a set of properties, or null otherwise.
     */
    static class PropertyOr extends ItemMap
    {
        Map<String, Json> condition;
        
        public PropertyOr(Map<String, Json> condition) { this.condition = condition; }
        
        public Json eval(Json entity)
        {
            for (Map.Entry<String, Json> e : condition.entrySet())
                if (entity.is(e.getKey(), e.getValue()))
                    return entity;
            return Json.nil();
        }
    }
    
    /**
     * Creates a {@link PropertyOr} predicate (checking that any of a number of properties is
     * present in a Json object) as follows: the name parameter is assumed to be of the form
     * <code>operator:group:P</code> and <code>pattern.at(name)</code> will have some value V.
     * Any other property name in pattern that has the same form, with the same operator and
     * the same group will be taken as well. All pairs (P,V) make up the map used to construct 
     * the PropertyOr predicate. 
     * 
     *  The above paragraph was written by reverse engineering the code years after it was
     *  written. So I'm not sure anymore what the original intent was. Clearly the group portion
     *  identifies a group, but what is the purpose of the operator?
     */
    static ItemMap collectPropertyGroup(String name, Json pattern)
    {
        Map<String, Json> values = new HashMap<String, Json>();
        String [] parts = name.split(":");
        String operator = parts[0];
        String groupname = parts[1];
        values.put(parts[2], pattern.at(name));
        pattern.delAt(name);
        for (Map.Entry<String, Json> e : pattern.asJsonMap().entrySet())
        {
            String next = e.getKey();
            if (!next.startsWith(operator))
                continue;
            String [] nextParts = next.split(":");
            if (!nextParts[1].equals(groupname))
                continue;
            if (!nextParts[0].equals(operator))
                throw new IllegalArgumentException("Different operator " + nextParts[0] + 
                        " for logical grouping " + groupname + ", expecting " + parts[0]);
            values.put(nextParts[2], e.getValue());
            pattern.delAt(next);
        }
        return new PropertyOr(values);
    }
    
    @SuppressWarnings("unchecked")
    static Collection<ItemMap> collectMaps(Json pattern)
    {
        Set<ItemMap> S = new HashSet<ItemMap>();
        for (Map.Entry<String, Json> e : pattern.asJsonMap().entrySet())
        {
            String name = e.getKey();
            // If name starts with an operator, it spans multiple properties
            if (!Character.isLetter(name.charAt(0)))
            {
                S.add(collectPropertyGroup(name, pattern));
                continue;
            }
            if (Character.isLetterOrDigit(name.charAt(name.length() - 1)))
                continue;
            int at = name.length() - 1;
            while (!Character.isLetterOrDigit(name.charAt(at)))
                at--;
            String op = name.substring(at + 1);
            if (op.equals("~="))
            {
                S.add(new RegExFilter(name.substring(0, at + 1), e.getValue().asString()));
            }
            else if (op.equals("@="))
            {
                String [] keywords = null;
                if (e.getValue().isString())
                    keywords = e.getValue().asString().split("[ \t,]+");
                else if (e.getValue().isArray())
                    keywords = (String[])((List<String>)e.getValue().getValue()).toArray(new String[0]);
                if (keywords.length > 0)
                    S.add(new KeywordMatch(name.substring(0, at + 1), keywords, false));
            }
            else
            {
               // unknown operator are ignored and just remain part of the name of the JSON property
            }
            pattern.delAt(name);
        }
        return S;
    }
    
    @SuppressWarnings("unchecked")
	static HGSearchResult<HGHandle> findExactObject(final HyperNodeJson node, Json j)
    {
    	if (!j.isObject())
    		return (HGSearchResult<HGHandle>) HGSearchResult.EMPTY; 
    	
        HGHandle [] A = new HGHandle[j.asJsonMap().size()];
        int i = 0;
        for (Map.Entry<String, Json> e : j.asJsonMap().entrySet())
        {
        	HGHandle propHandle = node.findProperty(e.getKey(), e.getValue());
        	if (propHandle == null)
        		return (HGSearchResult<HGHandle>) HGSearchResult.EMPTY;
            A[i++] = propHandle;
        }
        return node.graph().find(hg.and(hg.type(JsonTypeSchema.objectTypeHandle), 
		                                hg.link(A), 
		                                 hg.arity(i)));
    }
    
    @SuppressWarnings("unchecked")
    static HGSearchResult<HGHandle> findObjectPattern(final HyperNodeJson node, Json pattern, final boolean exact)
    {
    	if (exact)
    		return findExactObject(node, pattern);
        pattern = pattern.dup();        
        Mapping<HGHandle, Boolean> themap = null;
        if (!exact)
        {
	        final Collection<ItemMap> maps = collectMaps(pattern);
	        if (!maps.isEmpty())
	        {
	            themap = new Mapping<HGHandle, Boolean>()
	            {
	                public Boolean eval(HGHandle h)
	                {
	                    Json j = node.get(h);
	                    for (ItemMap m : maps)
	                    {
	                        j = m.eval(j);
	                        if (j.isNull())
	                            return false;
	                    }
	                    return true;
	                }
	            };
	        }
        }
        HGSearchResult<HGHandle> [] propertyCandidates = new HGSearchResult[pattern.asJsonMap().size()];
        int i = 0;
        for (Map.Entry<String, Json> e : pattern.asJsonMap().entrySet())
        {
        	for (JsonProperty prop : findAllProperties(node, e.getKey(), e.getValue()))
        		System.out.println(node.get(prop.getName()) + " = " + node.get(prop.getValue()));
        	propertyCandidates[i] = node.findPropertyPattern(e.getKey(), e.getValue());
        	if (!propertyCandidates[i].hasNext())
        	{
        		for (int j = i; j >= 0; j--)
        			HGUtils.closeNoException(propertyCandidates[j]);
        		propertyCandidates = null;
        		break;
        	}
        	i++;
        }
        if (propertyCandidates != null)        	
        {
            HGSearchResult<HGHandle> rs = new PipedResult<List<HGHandle>, HGHandle>(
            	new CrossProductResultSet<HGHandle>(propertyCandidates),
            	new AbstractKeyBasedQuery<List<HGHandle>, HGHandle>()
            	{
        			public HGSearchResult<HGHandle> execute()
        			{
        				And and = hg.and(hg.type(JsonTypeSchema.objectTypeHandle), hg.link(getKey()));
        				return node.find(and); 
        			} 
            	},
            	true
            ); 
            if (themap == null)
                return rs;
            else
                return new FilteredResultSet<HGHandle>(rs, themap, 0);
        }
        else
            return (HGSearchResult<HGHandle>) HGSearchResult.EMPTY;
        
    }
    
	@SuppressWarnings("unchecked")
	public static <T> HGRandomAccessResult<T> empty()
	{
		return (HGRandomAccessResult<T>) HGSearchResult.EMPTY;
	}

	public static <T> HGSearchResult<T> pipeCrossProductToCompiledQuery(
			final CrossProductResultSet<T> crossProduct,
			final HGQuery<T> compiledQuery, final String... varnames)
	{
		KeyBasedQuery<List<T>, T> propQuery = new KeyBasedQuery<List<T>, T>()
		{
			List<T> key = null;

			public void setKey(List<T> key)
			{
				this.key = key;
			}

			@Override
			public List<T> getKey()
			{
				return this.key;
			}

			@Override
			public HGSearchResult<T> execute()
			{
				for (int i = 0; i < varnames.length; i++)
					compiledQuery.var(varnames[i], key.get(i));
				return compiledQuery.execute();
			}
		};
		return new PipedResult<List<T>, T>(crossProduct, propQuery, true);
	}

	public static <T> HGSearchResult<T> pipeCrossProductToQuery(final CrossProductResultSet<T> crossProduct,
																final HGQuery<T> compiledQuery, 
																final String... varnames)
	{
		KeyBasedQuery<List<T>, T> propQuery = new KeyBasedQuery<List<T>, T>()
		{
			List<T> key = null;

			public void setKey(List<T> key)
			{
				this.key = key;
			}

			@Override
			public List<T> getKey()
			{
				return this.key;
			}

			@Override
			public HGSearchResult<T> execute()
			{
				for (int i = 0; i < varnames.length; i++)
					compiledQuery.var(varnames[i], key.get(0));
				return compiledQuery.execute();
			}
		};
		return new PipedResult<List<T>, T>(crossProduct, propQuery, true);
	}

	public static abstract class AbstractKeyBasedQuery<Key, Value> extends KeyBasedQuery<Key, Value>
	{
		Key key = null;

		public void setKey(Key key)
		{
			this.key = key;
		}

		@Override
		public Key getKey()
		{
			return this.key;
		}
	};	
	
	public static List<JsonProperty> findAllProperties(HyperNodeJson node, String namePattern, Object valuePattern)
	{
		List<JsonProperty> L = new ArrayList<JsonProperty>();
		try (HGSearchResult<HGHandle> rs = node.findPropertyPattern(namePattern, valuePattern))
		{
			while (rs.hasNext())
			{
				JsonProperty prop = node.get(rs.next());
				L.add(prop);
			}
		}
		return L;
	}
    
}