package org.molgenis.ontology.matching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.molgenis.data.DataService;
import org.molgenis.data.Entity;
import org.molgenis.data.EntityMetaData;
import org.molgenis.data.QueryRule;
import org.molgenis.data.QueryRule.Operator;
import org.molgenis.data.elasticsearch.SearchService;
import org.molgenis.data.support.MapEntity;
import org.molgenis.data.support.QueryImpl;
import org.molgenis.ontology.OntologyServiceResult;
import org.molgenis.ontology.beans.Ontology;
import org.molgenis.ontology.beans.OntologyImpl;
import org.molgenis.ontology.beans.OntologyServiceResultImpl;
import org.molgenis.ontology.beans.OntologyTerm;
import org.molgenis.ontology.beans.OntologyTermImpl;
import org.molgenis.ontology.model.OntologyMetaData;
import org.molgenis.ontology.model.OntologyTermMetaData;
import org.molgenis.ontology.model.OntologyTermNodePathMetaData;
import org.molgenis.ontology.model.OntologyTermSynonymMetaData;
import org.molgenis.ontology.repository.OntologyTermQueryRepository;
import org.molgenis.ontology.utils.NGramMatchingModel;
import org.molgenis.ontology.utils.OntologyServiceUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.tartarus.snowball.ext.PorterStemmer;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Sets;

public class OntologyMatchingServiceImpl implements OntologyMatchingService
{
	private final PorterStemmer stemmer = new PorterStemmer();
	private static final List<String> ELASTICSEARCH_RESERVED_WORDS = Arrays.asList("or", "and", "if");
	private static final String NON_WORD_SEPARATOR = "[^a-zA-Z0-9]";
	private static final String FUZZY_MATCH_SIMILARITY = "~0.8";
	private static final int MAX_NUMBER_MATCHES = 500;

	// Global fields that are used by other classes
	public static final String SIGNIFICANT_VALUE = "Significant";
	public static final Character DEFAULT_SEPARATOR = ';';
	public static final String DEFAULT_MATCHING_NAME_FIELD = "Name";
	public static final String DEFAULT_MATCHING_SYNONYM_FIELD = "Synonym";
	public static final String DEFAULT_MATCHING_IDENTIFIER = "Identifier";
	public static final String SCORE = "Score";
	public static final String MAX_SCORE_FIELD = "maxScoreField";
	public static final String COMBINED_SCORE = "Combined_Score";

	private final DataService dataService;
	private final SearchService searchService;

	@Autowired
	public OntologyMatchingServiceImpl(DataService dataService, SearchService searchService)
	{
		if (dataService == null) throw new IllegalArgumentException("DataService is null");
		if (searchService == null) throw new IllegalArgumentException("SearchService is null");
		this.dataService = dataService;
		this.searchService = searchService;
	}

	@Override
	public Iterable<Ontology> getAllOntologies()
	{
		Iterable<Ontology> ontologies = FluentIterable.from(getAllOntologyEntities()).transform(
				new Function<Entity, Ontology>()
				{
					@Override
					public Ontology apply(Entity entity)
					{
						return new OntologyImpl(entity);
					}
				});

		return ontologies;
	}

	@Override
	public Iterable<Entity> getAllOntologyEntities()
	{
		return dataService.findAll(OntologyMetaData.ENTITY_NAME);
	}

	@Override
	public Ontology getOntology(String ontologyIri)
	{
		Entity ontologyEntity = getOntologyEntity(ontologyIri);
		return ontologyEntity == null ? null : new OntologyImpl(ontologyEntity);
	}

	@Override
	public Entity getOntologyEntity(String ontologyIri)
	{
		return dataService.findOne(OntologyMetaData.ENTITY_NAME,
				new QueryImpl().eq(OntologyMetaData.ONTOLOGY_IRI, ontologyIri));
	}

	@Override
	public Iterable<OntologyTerm> findOntologyTerms(String queryTerm, String ontologyIri)
	{
		return null;
	}

	@Override
	public OntologyTerm getOntologyTerm(String ontologyTermIri, String ontologyIri)
	{
		Entity ontologyTermEntity = getOntologyTermEntity(ontologyTermIri, ontologyIri);
		return ontologyTermEntity == null ? null : new OntologyTermImpl(ontologyTermEntity);
	}

	@Override
	public Entity getOntologyTermEntity(String ontologyTermIri, String ontologyIri)
	{
		Entity ontologyEntity = getOntologyEntity(ontologyIri);
		if (ontologyEntity != null)
		{
			return dataService.findOne(
					OntologyTermMetaData.ENTITY_NAME,
					new QueryImpl().eq(OntologyTermMetaData.ONTOLOGY_TERM_IRI, ontologyTermIri).and()
							.eq(OntologyTermMetaData.ONTOLOGY, ontologyEntity));
		}
		return null;
	}

	@Override
	public List<String> getOntologyTermSynonyms(String ontologyTermIri, String ontologyIri)
	{
		List<String> synonyms = new ArrayList<String>();
		Entity ontologyTermEntity = getOntologyTermEntity(ontologyTermIri, ontologyIri);
		if (ontologyTermEntity != null)
		{
			Iterable<Entity> synonymEntities = ontologyTermEntity
					.getEntities(OntologyTermMetaData.ONTOLOGY_TERM_SYNONYM);
			synonyms = FluentIterable.from(synonymEntities).transform(new Function<Entity, String>()
			{
				@Override
				public String apply(Entity entity)
				{
					return entity.getString(OntologyTermSynonymMetaData.ONTOLOGY_TERM_SYNONYM);
				}
			}).toList();
		}
		return synonyms;
	}

	@Override
	public Iterable<OntologyTerm> getAllOntologyTerms(String ontologyIri)
	{
		Iterable<Entity> allOntologyEntities = getAllOntologyEntities();
		if (allOntologyEntities != null)
		{
			return FluentIterable.from(allOntologyEntities).transform(new Function<Entity, OntologyTerm>()
			{
				@Override
				public OntologyTerm apply(Entity entity)
				{
					return new OntologyTermImpl(entity);
				}
			});
		}
		return null;
	}

	@Override
	public Iterable<Entity> getAllOntologyTermEntities(String ontologyIri)
	{
		Entity ontologyEntity = getOntologyEntity(ontologyIri);
		if (ontologyEntity != null)
		{
			return dataService.findAll(OntologyTermMetaData.ENTITY_NAME,
					new QueryImpl().eq(OntologyTermMetaData.ONTOLOGY, ontologyEntity));
		}
		return null;
	}

	@Override
	public Iterable<OntologyTerm> getRootOntologyTerms(String ontologyIri)
	{
		Iterable<Entity> rootOntologyTermEntities = getRootOntologyTermEntities(ontologyIri);

		if (rootOntologyTermEntities != null)
		{
			return FluentIterable.from(rootOntologyTermEntities).transform(new Function<Entity, OntologyTerm>()
			{
				@Override
				public OntologyTerm apply(Entity entity)
				{
					return new OntologyTermImpl(entity);
				}
			});
		}
		return null;
	}

	@Override
	public Iterable<Entity> getRootOntologyTermEntities(String ontologyIri)
	{
		// TODO : FIXME, we need a better way to retrieve the root ontology terms
		Iterable<Entity> nodePathEntities = dataService.findAll(OntologyTermNodePathMetaData.ENTITY_NAME,
				new QueryImpl().eq(OntologyTermNodePathMetaData.ROOT, true));
		if (nodePathEntities != null)
		{
			return dataService.findAll(
					OntologyTermMetaData.ENTITY_NAME,
					new QueryImpl().eq(OntologyTermMetaData.ONTOLOGY_TERM_IRI, ontologyIri).and()
							.in(OntologyTermMetaData.ONTOLOGY_TERM_NODE_PATH, nodePathEntities));
		}
		return null;
	}

	@Override
	public Iterable<OntologyTerm> getChildOntologyTerms(String ontologyIri, String ontologyTermIri)
	{
		return null;
	}

	@Override
	public Iterable<Entity> getChildOntologyTermEntities(String ontologyIri, String ontologyTermIri)
	{
		Entity ontologyTerm = getOntologyTermEntity(ontologyTermIri, ontologyIri);
		Iterable<Entity> nodePathEntities = ontologyTerm.getEntities(OntologyTermMetaData.ONTOLOGY_TERM_NODE_PATH);
		Iterator<Entity> iterator = nodePathEntities.iterator();
		if (iterator.hasNext())
		{
			Entity firstNodePathEntity = iterator.next();

			String parentNodePath = firstNodePathEntity.getString(OntologyTermNodePathMetaData.ONTOLOGY_TERM_NODE_PATH);
			Iterable<Entity> childNodePathEntities = dataService.findAll(OntologyTermNodePathMetaData.ENTITY_NAME,
					new QueryImpl().like(OntologyTermNodePathMetaData.ONTOLOGY_TERM_NODE_PATH, parentNodePath + "%"));
			return dataService.findAll(OntologyTermMetaData.ENTITY_NAME,
					new QueryImpl().in(OntologyTermMetaData.ONTOLOGY_TERM_NODE_PATH, childNodePathEntities));
		}
		return null;
	}

	@Override
	public OntologyServiceResult searchEntity(String ontologyIri, Entity inputEntity)
	{
		List<Entity> relevantEntities = new ArrayList<Entity>();

		List<QueryRule> rulesForOntologyTermFields = new ArrayList<QueryRule>();
		List<QueryRule> rulesForOtherFields = new ArrayList<QueryRule>();
		for (String attributeName : inputEntity.getAttributeNames())
		{
			if (StringUtils.isNotEmpty(inputEntity.getString(attributeName))
					&& !attributeName.equalsIgnoreCase(DEFAULT_MATCHING_IDENTIFIER))
			{
				// The attribute name is either equal to 'Name' or starts
				// with string 'Synonym'
				if (DEFAULT_MATCHING_NAME_FIELD.equalsIgnoreCase(attributeName)
						|| attributeName.toLowerCase().startsWith(DEFAULT_MATCHING_SYNONYM_FIELD.toLowerCase()))
				{
					String medicalStemProxy = fuzzyMatchQuerySyntax(inputEntity.getString(attributeName));
					if (StringUtils.isNotEmpty(medicalStemProxy))
					{
						rulesForOntologyTermFields.add(new QueryRule(OntologyTermMetaData.ONTOLOGY_TERM_SYNONYM,
								Operator.FUZZY_MATCH, medicalStemProxy));
					}
				}
				else if (StringUtils.isNotEmpty(inputEntity.getString(attributeName)))
				{
					rulesForOtherFields.add(new QueryRule(attributeName, Operator.EQUALS, inputEntity
							.getString(attributeName)));
				}
			}
		}

		List<QueryRule> combinedRules = new ArrayList<QueryRule>();

		if (rulesForOntologyTermFields.size() > 0)
		{
			QueryRule disMaxQuery_1 = new QueryRule(rulesForOntologyTermFields);
			disMaxQuery_1.setOperator(Operator.DIS_MAX);
			combinedRules.add(disMaxQuery_1);
		}

		if (rulesForOtherFields.size() > 0)
		{
			QueryRule disMaxQuery_2 = new QueryRule(rulesForOtherFields);
			disMaxQuery_2.setOperator(Operator.DIS_MAX);
			combinedRules.add(disMaxQuery_2);
		}

		if (combinedRules.size() > 0)
		{
			QueryRule queryRule = new QueryRule(combinedRules);
			queryRule.setOperator(Operator.DIS_MAX);

			EntityMetaData entityMetaData = dataService.getEntityMetaData(OntologyTermMetaData.ENTITY_NAME);
			for (Entity entity : searchService.search(new QueryImpl(queryRule).pageSize(MAX_NUMBER_MATCHES),
					entityMetaData))
			{
				String maxScoreField = null;
				double maxNgramScore = 0;
				for (String inputAttrName : inputEntity.getAttributeNames())
				{
					if (StringUtils.isNotEmpty(inputEntity.getString(inputAttrName)))
					{
						if (DEFAULT_MATCHING_NAME_FIELD.equalsIgnoreCase(inputAttrName)
								|| inputAttrName.toLowerCase().startsWith(DEFAULT_MATCHING_SYNONYM_FIELD.toLowerCase()))
						{
							double ngramScore = calculateNGramOTSynonyms(inputEntity.getString(inputAttrName), entity);
							if (maxNgramScore < ngramScore)
							{
								maxNgramScore = ngramScore;
								maxScoreField = inputAttrName;
							}
						}
						else
						{
							// TODO : implement the scenario where database annotations are used in matching
						}
					}
				}
				MapEntity mapEntity = new MapEntity();
				for (String attributeName : entity.getAttributeNames())
				{
					mapEntity.set(attributeName, entity.get(attributeName));
				}
				mapEntity.set(SCORE, maxNgramScore);
				mapEntity.set(COMBINED_SCORE, maxNgramScore);
				mapEntity.set(MAX_SCORE_FIELD, maxScoreField);
				relevantEntities.add(mapEntity);
			}
		}
		return convertResults(relevantEntities, OntologyServiceUtil.getEntityAsMap(inputEntity));
	}

	@Override
	public OntologyServiceResult search(String ontologyUrl, String queryString)
	{
		return null;
	}

	private OntologyServiceResult convertResults(List<Entity> relevantEntities, Map<String, Object> inputData)
	{
		Collections.sort(relevantEntities, new Comparator<Entity>()
		{
			public int compare(Entity entity1, Entity entity2)
			{
				return entity2.getDouble(COMBINED_SCORE).compareTo(entity1.getDouble(COMBINED_SCORE));
			}
		});
		// PostProcessOntologyTermCombineSynonymAlgorithm.process(relevantEntities, inputData);
		// PostProcessRemoveRedundantOntologyTerm.process(relevantEntities);
		// PostProcessRedistributionScoreAlgorithm.process(relevantEntities, inputData, this);
		return new OntologyServiceResultImpl(inputData, relevantEntities, relevantEntities.size());
	}

	/**
	 * A helper function to calculate the best NGram score from a list ontologyTerm synonyms
	 * 
	 * @param queryString
	 * @param entity
	 * @return
	 */
	private double calculateNGramOTSynonyms(String queryString, Entity entity)
	{
		double ngramScore = 0;
		queryString = removeIllegalCharWithSingleWhiteSpace(queryString);
		for (Entity synonymEntity : entity.getEntities(OntologyTermMetaData.ONTOLOGY_TERM_SYNONYM))
		{
			String ontologyTermSynonym = removeIllegalCharWithSingleWhiteSpace(synonymEntity
					.getString(OntologyTermSynonymMetaData.ONTOLOGY_TERM_SYNONYM));
			double score_1 = NGramMatchingModel.stringMatching(queryString, ontologyTermSynonym);
			if (score_1 > ngramScore) ngramScore = score_1;
		}
		return ngramScore;
	}

	/**
	 * A helper function to produce fuzzy match query in elasticsearch
	 * 
	 * @param queryString
	 * @return
	 */
	private String fuzzyMatchQuerySyntax(String queryString)
	{
		StringBuilder stringBuilder = new StringBuilder();
		Set<String> uniqueTerms = Sets.newHashSet(queryString.toLowerCase().trim().split(NON_WORD_SEPARATOR));
		uniqueTerms.removeAll(NGramMatchingModel.STOPWORDSLIST);
		for (String term : uniqueTerms)
		{
			if (StringUtils.isNotEmpty(term.trim()) && !(ELASTICSEARCH_RESERVED_WORDS.contains(term)))
			{
				stemmer.setCurrent(removeIllegalCharWithEmptyString(term));
				stemmer.stem();
				String afterStem = stemmer.getCurrent();
				if (StringUtils.isNotEmpty(afterStem))
				{
					stringBuilder.append(afterStem).append(FUZZY_MATCH_SIMILARITY)
							.append(OntologyTermQueryRepository.SINGLE_WHITESPACE);
				}
			}
		}
		return stringBuilder.toString().trim();
	}

	public String removeIllegalCharWithSingleWhiteSpace(String string)
	{
		return string.replaceAll(OntologyTermQueryRepository.ILLEGAL_CHARACTERS_PATTERN,
				OntologyTermQueryRepository.SINGLE_WHITESPACE);
	}

	public String removeIllegalCharWithEmptyString(String string)
	{
		return string.replaceAll(OntologyTermQueryRepository.ILLEGAL_CHARACTERS_PATTERN, StringUtils.EMPTY);
	}
}