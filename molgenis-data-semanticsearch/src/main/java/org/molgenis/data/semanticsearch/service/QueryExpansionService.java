package org.molgenis.data.semanticsearch.service;

import org.molgenis.data.QueryRule;
import org.molgenis.data.semanticsearch.service.bean.SearchParam;

public interface QueryExpansionService
{
	public abstract QueryRule expand(SearchParam searchParam);
}