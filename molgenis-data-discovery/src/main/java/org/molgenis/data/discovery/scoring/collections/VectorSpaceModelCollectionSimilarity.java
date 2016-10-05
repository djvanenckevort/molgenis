package org.molgenis.data.discovery.scoring.collections;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.molgenis.data.discovery.model.biobank.BiobankSampleCollection;
import org.molgenis.data.discovery.model.biobank.BiobankUniverse;
import org.molgenis.data.discovery.model.biobank.BiobankUniverseMemberVector;
import org.molgenis.data.discovery.model.matching.BiobankSampleCollectionSimilarity;
import org.molgenis.data.discovery.model.matching.OntologyTermRelated;
import org.molgenis.data.discovery.repo.BiobankUniverseRepository;
import org.molgenis.data.semanticsearch.semantic.Hit;
import org.molgenis.ontology.core.model.OntologyTermImpl;
import org.molgenis.ontology.core.model.SemanticType;
import org.molgenis.ontology.core.service.OntologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * This class creates the {@link BiobankUniverseMemberVector} representations for all the
 * {@link BiobankSampleCollection}s in the {@link BiobankUniverse}
 * 
 * @author chaopang
 *
 */
public class VectorSpaceModelCollectionSimilarity
{
	private BiobankUniverseRepository biobankUniverseRepository;
	private OntologyService ontologyService;
	private static final Logger LOG = LoggerFactory.getLogger(VectorSpaceModelCollectionSimilarity.class);

	final static int DISTANCE = 5;

	private LoadingCache<OntologyTermRelated, Double> cachedOntologyTermSemanticRelateness = CacheBuilder.newBuilder()
			.maximumSize(2000).expireAfterWrite(1, TimeUnit.HOURS).build(new CacheLoader<OntologyTermRelated, Double>()
			{
				public Double load(OntologyTermRelated ontologyTermRelated)
				{
					if (ontologyService.related(ontologyTermRelated.getTarget(), ontologyTermRelated.getSource(),
							ontologyTermRelated.getStopLevel()))
					{
						return ontologyService.getOntologyTermSemanticRelatedness(ontologyTermRelated.getTarget(),
								ontologyTermRelated.getSource());
					}
					return 0.0d;
				}
			});

	public VectorSpaceModelCollectionSimilarity(BiobankUniverseRepository biobankUniverseRepository,
			OntologyService ontologyService)
	{
		this.biobankUniverseRepository = requireNonNull(biobankUniverseRepository);
		this.ontologyService = requireNonNull(ontologyService);
	}

	public List<BiobankUniverseMemberVector> createBiobankUniverseMemberVectors(BiobankUniverse biobankUniverse)
	{
		List<BiobankSampleCollection> biobankSampleCollections = biobankUniverse.getMembers();

		List<Map<OntologyTermImpl, Integer>> collect = biobankSampleCollections.stream()
				.map(collection -> getOntologyTermFrequency(collection, biobankUniverse)).collect(toList());

		List<OntologyTermImpl> uniqueOntologyTermList = collect.stream().flatMap(map -> map.keySet().stream()).distinct()
				.collect(toList());

		List<double[]> vectors = collect.stream()
				.map(ontologyTermFrequency -> createVector(ontologyTermFrequency, uniqueOntologyTermList))
				.collect(toList());

		List<BiobankUniverseMemberVector> biobankSampleCollectionVectors = biobankSampleCollections.stream()
				.map(biobankSampleCollections::indexOf).map(index -> BiobankUniverseMemberVector
						.create(biobankSampleCollections.get(index), vectors.get(index)))
				.collect(toList());

		return biobankSampleCollectionVectors;
	}

	/**
	 * Compute the cosine angle between two {@link BiobankUniverseMemberVector}s
	 * 
	 * @param biobankUniverseMemberVectorOne
	 * @param biobankUniverseMemberVectorTwo
	 * @return cosine angle
	 */
	public BiobankSampleCollectionSimilarity cosineValue(BiobankUniverseMemberVector biobankUniverseMemberVectorOne,
			BiobankUniverseMemberVector biobankUniverseMemberVectorTwo)
	{
		double[] vectorOne = biobankUniverseMemberVectorOne.getPoint();

		double[] vectorTwo = biobankUniverseMemberVectorTwo.getPoint();

		float docProduct = 0.0f;

		if (vectorOne.length != vectorTwo.length)
			return BiobankSampleCollectionSimilarity.create(biobankUniverseMemberVectorOne.getBiobankSampleCollection(),
					biobankUniverseMemberVectorTwo.getBiobankSampleCollection(), docProduct);

		for (int i = 0; i < vectorOne.length; i++)
		{
			docProduct += vectorOne[i] * vectorTwo[i];
		}

		float cosineSimilarity = (docProduct / (euclideanNorms(vectorOne) * euclideanNorms(vectorTwo)));

		return BiobankSampleCollectionSimilarity.create(biobankUniverseMemberVectorOne.getBiobankSampleCollection(),
				biobankUniverseMemberVectorTwo.getBiobankSampleCollection(), cosineSimilarity);
	}

	double[] createVector(Map<OntologyTermImpl, Integer> targetOntologyTermFrequency,
			List<OntologyTermImpl> uniqueOntologyTermList)
	{
		Set<OntologyTermImpl> uniqueTargetOntologyTerms = targetOntologyTermFrequency.keySet();

		// For the unmatched ontology terms, we try to pair them with the closest neighbor in the ontology structure
		List<Hit<OntologyTermRelated>> relatedOntologyTerms = uniqueTargetOntologyTerms.stream()
				.flatMap(ot -> findRelatedOntologyTerms(ot, uniqueOntologyTermList).stream())
				.collect(toList());

		double[] vector = new double[uniqueOntologyTermList.size()];

		for (Hit<OntologyTermRelated> relatedOntologyTermHit : relatedOntologyTerms)
		{
			OntologyTermRelated ontologyTermRelated = relatedOntologyTermHit.getResult();
			OntologyTermImpl sourceOntologyTerm = ontologyTermRelated.getSource();
			int index = uniqueOntologyTermList.indexOf(sourceOntologyTerm);
			vector[index] = vector[index] + relatedOntologyTermHit.getScore();
		}

		return vector;
	}

	private float euclideanNorms(double[] vector)
	{
		double sum = DoubleStream.of(vector).map(f -> Math.pow(f, 2.0)).sum();
		return (float) Math.sqrt(sum);
	}

	private List<Hit<OntologyTermRelated>> findRelatedOntologyTerms(OntologyTermImpl targetOntologyTerm,
			Collection<OntologyTermImpl> allOntologyTerms)
	{
		List<Hit<OntologyTermRelated>> ontologyTermHits = new ArrayList<>();

		for (OntologyTermImpl sourceOntologyTerm : allOntologyTerms)
		{
			try
			{
				OntologyTermRelated create = OntologyTermRelated.create(targetOntologyTerm, sourceOntologyTerm,
						DISTANCE);

				Double relatedness = cachedOntologyTermSemanticRelateness.get(create);

				if (relatedness != 0)
				{
					ontologyTermHits.add(Hit.create(create, relatedness.floatValue()));
				}
			}
			catch (ExecutionException e)
			{
				LOG.error(e.getMessage());
			}
		}
		return ontologyTermHits;
	}

	private List<OntologyTermImpl> getAllOntologyTerms(BiobankSampleCollection biobankSampleCollection,
			BiobankUniverse biobankUniverse)
	{
		List<SemanticType> semanticTypeFilter = biobankUniverse.getKeyConcepts();

		List<OntologyTermImpl> ontologyTermImpls = biobankUniverseRepository.getBiobankSampleAttributes(biobankSampleCollection)
				.stream().flatMap(attribute -> attribute.getTagGroups().stream())
				.flatMap(tag -> tag.getOntologyTermImpls().stream().distinct())
				.filter(ot -> ot.getSemanticTypes().stream().allMatch(st -> !semanticTypeFilter.contains(st)))
				.collect(Collectors.toList());

		return ontologyTermImpls;
	}

	private Map<OntologyTermImpl, Integer> getOntologyTermFrequency(BiobankSampleCollection biobankSampleCollection,
			BiobankUniverse biobankUniverse)
	{
		List<OntologyTermImpl> ontologyTermImpls = getAllOntologyTerms(biobankSampleCollection, biobankUniverse);

		Map<OntologyTermImpl, Integer> ontologyTermFrequency = ontologyTermImpls.stream().distinct()
				.collect(toMap(ot -> ot, ot -> 0));

		for (OntologyTermImpl ot : ontologyTermImpls)
		{
			ontologyTermFrequency.put(ot, ontologyTermFrequency.get(ot) + 1);
		}

		return ontologyTermFrequency;
	}
}