package org.molgenis.data.discovery.model.matching;

import java.util.List;

import org.molgenis.gson.AutoGson;
import org.molgenis.ontology.core.model.OntologyTerm;

import com.google.auto.value.AutoValue;
import org.molgenis.ontology.core.model.OntologyTermImpl;

@AutoValue
@AutoGson(autoValueClass = AutoValue_MatchingExplanation.class)
public abstract class MatchingExplanation
{
	public abstract String getIdentifier();

	public abstract List<OntologyTermImpl> getOntologyTerms();

	public abstract String getQueryString();

	public abstract String getMatchedWords();

	public abstract double getNgramScore();

	public static MatchingExplanation create(String identifier, List<OntologyTermImpl> ontologyTermImpls, String queryString,
			String matchedWords, double ngramScore)
	{
		return new AutoValue_MatchingExplanation(identifier, ontologyTermImpls, queryString, matchedWords, ngramScore);
	}
}