package org.molgenis.data.discovery.service.impl;

import com.google.common.base.Joiner;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import org.molgenis.data.discovery.filters.DataTypePostFilter;
import org.molgenis.data.discovery.model.biobank.BiobankSampleAttribute;
import org.molgenis.data.discovery.model.biobank.BiobankUniverse;
import org.molgenis.data.discovery.model.matching.AttributeMappingCandidate;
import org.molgenis.data.discovery.model.matching.MatchingExplanation;
import org.molgenis.data.discovery.service.OntologyBasedExplainService;
import org.molgenis.data.populate.IdGenerator;
import org.molgenis.data.semanticsearch.semantic.Hit;
import org.molgenis.data.semanticsearch.service.bean.SearchParam;
import org.molgenis.ontology.core.model.OntologyTerm;
import org.molgenis.ontology.core.model.SemanticType;
import org.molgenis.ontology.core.service.OntologyService;
import org.molgenis.ontology.utils.Stemmer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.*;
import static org.molgenis.data.discovery.service.impl.OntologyBasedMatcher.EXPANSION_LEVEL;
import static org.molgenis.data.discovery.service.impl.OntologyBasedMatcher.STOP_LEVEL;
import static org.molgenis.data.semanticsearch.utils.SemanticSearchServiceUtils.findMatchedWords;
import static org.molgenis.data.semanticsearch.utils.SemanticSearchServiceUtils.splitIntoUniqueTerms;
import static org.molgenis.ontology.utils.NGramDistanceAlgorithm.STOPWORDSLIST;
import static org.molgenis.ontology.utils.NGramDistanceAlgorithm.stringMatching;
import static org.molgenis.ontology.utils.Stemmer.splitAndStem;

/**
 * This is the new explain API that explains the candidate matches produced by {@link OntologyBasedMatcher}
 *
 * @author chaopang
 */
public class OntologyBasedExplainServiceImpl implements OntologyBasedExplainService
{
	private final static Logger LOG = LoggerFactory.getLogger(OntologyBasedExplainServiceImpl.class);
	private final Joiner termJoiner = Joiner.on(' ');
	private final DataTypePostFilter dataTypePostFilter = new DataTypePostFilter();

	private final IdGenerator idGenerator;
	private final OntologyService ontologyService;

	@Autowired
	public OntologyBasedExplainServiceImpl(IdGenerator idGenerator, OntologyService ontologyService)
	{
		this.idGenerator = requireNonNull(idGenerator);
		this.ontologyService = requireNonNull(ontologyService);
	}

	@Override
	public List<AttributeMappingCandidate> explain(BiobankUniverse biobankUniverse, SearchParam searchParam,
			BiobankSampleAttribute targetAttribute, List<BiobankSampleAttribute> sourceAttributes,
			AttributeCandidateScoringImpl attributeCandidateScoring)
	{
		Map<String, Boolean> cachedMatchedWordsHighQuality = new HashMap<>();

		List<AttributeMappingCandidate> candidates = new ArrayList<>();

		LOG.trace("Started explaining the matched source attributes");

		for (BiobankSampleAttribute sourceAttribute : sourceAttributes)
		{
			Multimap<OntologyTerm, OntologyTerm> relatedOntologyTerms = findAllRelatedOntologyTerms(targetAttribute,
					sourceAttribute, biobankUniverse);

			MatchingExplanation explanation = null;

			// OntologyTerms are involved in matching attributes
			if (!relatedOntologyTerms.isEmpty())
			{
				Hit<String> computeScore = attributeCandidateScoring
						.score(targetAttribute, sourceAttribute, biobankUniverse, relatedOntologyTerms,
								searchParam.getMatchParam().isStrictMatch());

				String matchedTargetWords = termJoiner
						.join(findMatchedWords(computeScore.getResult(), targetAttribute.getLabel()));

				String matchedSourceWords = termJoiner
						.join(findMatchedWords(computeScore.getResult(), sourceAttribute.getLabel()));

				List<OntologyTerm> ontologyTerms = relatedOntologyTerms.values().stream().distinct().collect(toList());

				explanation = MatchingExplanation
						.create(idGenerator.generateId(), ontologyTerms, computeScore.getResult(), matchedTargetWords,
								matchedSourceWords, computeScore.getScore());
			}
			else
			{
				String matchedWords = termJoiner
						.join(findMatchedWords(targetAttribute.getLabel(), sourceAttribute.getLabel()));

				double score = stringMatching(targetAttribute.getLabel(), sourceAttribute.getLabel()) / 100;

				explanation = MatchingExplanation
						.create(idGenerator.generateId(), emptyList(), targetAttribute.getLabel(), matchedWords,
								matchedWords, score);
			}

			// For those source attributes who get matched to the target with the same matched word, we cached the
			// 'quality' in a map so that we don't need to compute the quality twice
			String matchedWords = explanation.getMatchedTargetWords() + ' ' + explanation.getMatchedSourceWords();

			if (cachedMatchedWordsHighQuality.containsKey(matchedWords))
			{
				if (cachedMatchedWordsHighQuality.get(matchedWords))
				{
					candidates.add(AttributeMappingCandidate
							.create(idGenerator.generateId(), biobankUniverse, targetAttribute, sourceAttribute,
									explanation));
				}
			}
			else
			{
				if (isMatchHighQuality(explanation, searchParam, biobankUniverse))
				{
					candidates.add(AttributeMappingCandidate
							.create(idGenerator.generateId(), biobankUniverse, targetAttribute, sourceAttribute,
									explanation));

					cachedMatchedWordsHighQuality.put(matchedWords, true);
				}
				else
				{
					cachedMatchedWordsHighQuality.put(matchedWords, false);
				}
			}
		}

		LOG.trace("Finished explaining the matched source attributes");

		return candidates.stream().sorted().collect(Collectors.toList());
	}

	private boolean isMatchHighQuality(MatchingExplanation explanation, SearchParam searchParam,
			BiobankUniverse biobankUniverse)
	{
		if (explanation.getNgramScore() > searchParam.getMatchParam().getHighQualityThreshold())
		{
			return true;
		}

		List<OntologyTerm> ontologyTerms = explanation.getOntologyTerms();

		if (ontologyTerms.isEmpty())
		{
			ontologyTerms = ontologyService.findExactOntologyTerms(ontologyService.getAllOntologyIds(),
					splitIntoUniqueTerms(explanation.getMatchedWords()), 10);
		}

		List<SemanticType> conceptFilter = biobankUniverse.getKeyConcepts();

		Multimap<String, OntologyTerm> ontologyTermWithSameSynonyms = LinkedHashMultimap.create();

		Set<String> stemmedMatchedWords = splitAndStem(explanation.getMatchedWords());

		for (OntologyTerm ontologyTerm : ontologyTerms)
		{
			Optional<String> findFirst = ontologyTerm.getSynonyms().stream().map(Stemmer::splitAndStem)
					.filter(stemmedSynonymWords -> stemmedMatchedWords.containsAll(stemmedSynonymWords))
					.map(words -> words.stream().sorted().collect(joining(" "))).findFirst();

			if (findFirst.isPresent())
			{
				ontologyTermWithSameSynonyms.put(findFirst.get(), ontologyTerm);
			}
		}

		List<Collection<OntologyTerm>> collect = ontologyTermWithSameSynonyms.asMap().values().stream()
				.filter(ots -> areOntologyTermsImportant(conceptFilter, ots)).collect(toList());

		String matchedWords = splitIntoUniqueTerms(explanation.getMatchedSourceWords()).stream()
				.map(String::toLowerCase).filter(word -> !STOPWORDSLIST.contains(word)).collect(joining(" "));

		// TODO: for testing purpose
		return !collect.isEmpty() && matchedWords.length() >= 3;
		// return true;
	}

	private boolean areOntologyTermsImportant(List<SemanticType> conceptFilter, Collection<OntologyTerm> ots)
	{
		// Good ontology terms are defined as the ontology terms whose semantic types are global concepts and not in
		// the conceptFilter
		long countOfGoodOntologyTerms = ots.stream()
				.filter(ot -> ot.getSemanticTypes().isEmpty() || ot.getSemanticTypes().stream().allMatch(
						semanticType -> semanticType.isGlobalKeyConcept() && !conceptFilter.contains(semanticType)))
				.count();

		// Bad ontology terms are defined as the ontology terms whose any of the semantic types are not global
		// concepts or in the conceptFilter
		long countOfBadOntologyTerms = ots.stream()
				.filter(ot -> !ot.getSemanticTypes().isEmpty() && ot.getSemanticTypes().stream().anyMatch(
						semanticType -> !semanticType.isGlobalKeyConcept() || conceptFilter.contains(semanticType)))
				.count();

		// If there are more good ontology terms than the bad ones, we keep the ontology terms
		return countOfGoodOntologyTerms >= countOfBadOntologyTerms;
	}

	private Multimap<OntologyTerm, OntologyTerm> findAllRelatedOntologyTerms(BiobankSampleAttribute targetAttribute,
			BiobankSampleAttribute sourceAttribute, BiobankUniverse biobankUniverse)
	{
		Multimap<OntologyTerm, OntologyTerm> relatedOntologyTerms = LinkedHashMultimap.create();

		Set<OntologyTerm> targetOntologyTerms = getAllOntologyTerms(targetAttribute, biobankUniverse);

		Set<OntologyTerm> sourceOntologyTerms = getAllOntologyTerms(sourceAttribute, biobankUniverse);

		for (OntologyTerm targetOt : targetOntologyTerms)
		{
			for (OntologyTerm sourceOt : sourceOntologyTerms)
			{
				if (ontologyService.related(targetOt, sourceOt, STOP_LEVEL) && ontologyService
						.areWithinDistance(targetOt, sourceOt, EXPANSION_LEVEL))
				{
					relatedOntologyTerms.put(targetOt, sourceOt);
				}
			}
		}

		return relatedOntologyTerms;
	}

	private Set<OntologyTerm> getAllOntologyTerms(BiobankSampleAttribute biobankSampleAttribute,
			BiobankUniverse biobankUniverse)
	{
		List<SemanticType> conceptFilter = biobankUniverse.getKeyConcepts();

		return biobankSampleAttribute.getTagGroups().stream().flatMap(tagGroup -> tagGroup.getOntologyTerms().stream())
				.filter(ot -> areSemanticTypesImportant(ot, conceptFilter)).collect(toSet());
	}

	private boolean areSemanticTypesImportant(OntologyTerm ontologyTerm, List<SemanticType> conceptFilter)
	{
		List<SemanticType> semanticTypes = ontologyTerm.getSemanticTypes();
		for (SemanticType semanticType : semanticTypes)
		{
			if (conceptFilter.contains(semanticType)) return false;
		}
		return true;
	}
}