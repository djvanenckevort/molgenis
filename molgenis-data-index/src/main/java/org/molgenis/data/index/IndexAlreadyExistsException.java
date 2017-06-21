package org.molgenis.data.index;

public class IndexAlreadyExistsException extends IndexException
{
	private static final long serialVersionUID = 1L;

	public IndexAlreadyExistsException(String indexName)
	{
		super(String.format("Index '%s' already exists.", indexName));
	}
}