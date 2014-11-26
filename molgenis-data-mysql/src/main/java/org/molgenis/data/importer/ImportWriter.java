package org.molgenis.data.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.molgenis.data.AttributeMetaData;
import org.molgenis.data.CrudRepository;
import org.molgenis.data.DataService;
import org.molgenis.data.DatabaseAction;
import org.molgenis.data.Entity;
import org.molgenis.data.EntityMetaData;
import org.molgenis.data.IndexedRepository;
import org.molgenis.data.ManageableCrudRepositoryCollection;
import org.molgenis.data.MolgenisDataException;
import org.molgenis.data.Package;
import org.molgenis.data.Query;
import org.molgenis.data.Repository;
import org.molgenis.data.RepositoryCollection;
import org.molgenis.data.meta.TagMetaData;
import org.molgenis.data.meta.WritableMetaDataService;
import org.molgenis.data.support.QueryImpl;
import org.molgenis.data.support.TransformedEntity;
import org.molgenis.fieldtypes.FieldType;
import org.molgenis.framework.db.EntityImportReport;
import org.molgenis.security.core.utils.SecurityUtils;
import org.molgenis.security.permission.PermissionSystemService;
import org.molgenis.util.DependencyResolver;
import org.molgenis.util.HugeSet;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Writes the imported metadata and data to target {@link RepositoryCollection}.
 */
public class ImportWriter
{
	private final DataService dataService;
	private final WritableMetaDataService metaDataService;
	private final PermissionSystemService permissionSystemService;

	private final static Logger logger = Logger.getLogger(ImportWriter.class);

	/**
	 * Creates the ImportWriter
	 * 
	 * @param dataService
	 *            {@link DataService} to query existing repositories and transform entities
	 * @param metaDataService
	 *            {@link WritableMetaDataService} to add and update {@link EntityMetaData}
	 * @param permissionSystemService
	 *            {@link PermissionSystemService} to give permissions on uploaded entities
	 */
	public ImportWriter(DataService dataService, WritableMetaDataService metaDataService,
			PermissionSystemService permissionSystemService)
	{
		this.dataService = dataService;
		this.metaDataService = metaDataService;
		this.permissionSystemService = permissionSystemService;
	}

	@Transactional
	public EntityImportReport doImport(EmxImportJob job)
	{
		// TODO: parse the tags in the parser and put them in the parsedMetaData
		importTags(job.source);
		importPackages(job.parsedMetaData);
		addEntityMetaData(job.parsedMetaData, job.report, job.metaDataChanges, job.target);
		addEntityPermissions(job.metaDataChanges);
		importData(job.report, job.parsedMetaData.getEntities(), job.source, job.target, job.dbAction);
		return job.report;
	}

	/**
	 * Imports entity data for all entities in {@link #resolved} from {@link #source} to {@link #targetCollection}
	 */
	private void importData(EntityImportReport report, List<EntityMetaData> resolved, RepositoryCollection source,
			RepositoryCollection targetCollection, DatabaseAction dbAction)
	{
		for (final EntityMetaData entityMetaData : resolved)
		{
			String name = entityMetaData.getName();
			CrudRepository crudRepository = (CrudRepository) targetCollection.getRepositoryByEntityName(name);

			if (crudRepository != null)
			{
				Repository fileEntityRepository = source.getRepositoryByEntityName(entityMetaData.getSimpleName());

				if (fileEntityRepository == null)
				{
					// Try fully qualified name
					fileEntityRepository = source.getRepositoryByEntityName(entityMetaData.getName());
				}

				// check to prevent nullpointer when importing metadata only
				if (fileEntityRepository != null)
				{
					// transforms entities so that they match the entity meta data of the output repository
					Iterable<Entity> entities = Iterables.transform(fileEntityRepository,
							new Function<Entity, Entity>()
							{
								@Override
								public Entity apply(Entity entity)
								{
									return new TransformedEntity(entity, entityMetaData, dataService);
								}
							});
					entities = DependencyResolver.resolveSelfReferences(entities, entityMetaData);

					int count = update(crudRepository, entities, dbAction);
					report.addEntityCount(name, count);
				}
			}
		}
	}

	/**
	 * Gives the user permission to see and edit his imported entities, unless the user is admin since admins can do
	 * that anyways.
	 */
	private void addEntityPermissions(MetaDataChanges metaDataChanges)
	{
		if (!SecurityUtils.currentUserIsSu())
		{
			permissionSystemService.giveUserEntityAndMenuPermissions(SecurityContextHolder.getContext(),
					metaDataChanges.getAddedEntities());
		}
	}

	/**
	 * Adds the parsed {@link ParsedMetaData}, creating new repositories where necessary.
	 */
	private void addEntityMetaData(ParsedMetaData parsedMetaData, EntityImportReport report,
			MetaDataChanges metaDataChanges, ManageableCrudRepositoryCollection targetCollection)
	{
		for (EntityMetaData entityMetaData : parsedMetaData.getEntities())
		{
			String name = entityMetaData.getName();
			if (!EmxMetaDataParser.ENTITIES.equals(name) && !EmxMetaDataParser.ATTRIBUTES.equals(name)
					&& !EmxMetaDataParser.PACKAGES.equals(name) && !EmxMetaDataParser.TAGS.equals(name))
			{
				if (metaDataService.getEntityMetaData(entityMetaData.getName()) == null)
				{
					logger.debug("trying to create: " + name);
					metaDataChanges.addEntity(name);
					Repository repo = targetCollection.add(entityMetaData);
					if (repo != null)
					{
						report.addNewEntity(name);
					}
				}
				else if (!entityMetaData.isAbstract())
				{
					metaDataChanges.addAttributes(name, targetCollection.update(entityMetaData));
				}
			}
		}
	}

	/**
	 * Adds the packages from the packages sheet to the {@link #metaDataService}.
	 */
	private void importPackages(ParsedMetaData parsedMetaData)
	{
		for (Package p : parsedMetaData.getPackages().values())
		{
			if (p != null)
			{
				metaDataService.addPackage(p);
			}
		}
	}

	/**
	 * Imports the tags from the tag sheet.
	 */
	private void importTags(RepositoryCollection source)
	{
		Repository tagRepo = source.getRepositoryByEntityName(TagMetaData.ENTITY_NAME);
		if (tagRepo != null)
		{
			for (Entity tag : tagRepo)
			{
				Entity transformed = new TransformedEntity(tag, new TagMetaData(), dataService);
				Entity existingTag = dataService
						.findOne(TagMetaData.ENTITY_NAME, tag.getString(TagMetaData.IDENTIFIER));

				if (existingTag == null)
				{
					dataService.add(TagMetaData.ENTITY_NAME, transformed);
				}
				else
				{
					dataService.update(TagMetaData.ENTITY_NAME, transformed);
				}
			}
		}
	}

	/**
	 * Drops entities and added attributes and reindexes the entities whose attributes were modified.
	 */
	public void rollbackSchemaChanges(EmxImportJob job)
	{
		logger.info("Rolling back changes.");
		dropAddedEntities(job.target, job.metaDataChanges.getAddedEntities());
		List<String> entities = dropAddedAttributes(job.target, job.metaDataChanges.getAddedAttributes());

		// Reindex
		Set<String> entitiesToIndex = Sets.newLinkedHashSet(job.source.getEntityNames());
		entitiesToIndex.addAll(entities);
		entitiesToIndex.add("tags");
		entitiesToIndex.add("packages");
		entitiesToIndex.add("entities");
		entitiesToIndex.add("attributes");

		reindex(entitiesToIndex);
	}

	/**
	 * Reindexes entities
	 * 
	 * @param entitiesToIndex
	 *            Set of entity names
	 */
	private void reindex(Set<String> entitiesToIndex)
	{
		for (String entity : entitiesToIndex)
		{
			if (dataService.hasRepository(entity))
			{
				Repository repo = dataService.getRepositoryByEntityName(entity);
				if ((repo != null) && (repo instanceof IndexedRepository))
				{
					((IndexedRepository) repo).rebuildIndex();
				}
			}
		}
	}

	/**
	 * Drops attributes from entities
	 */
	private List<String> dropAddedAttributes(ManageableCrudRepositoryCollection targetCollection,
			ImmutableMap<String, Collection<AttributeMetaData>> addedAttributes)
	{
		List<String> entities = Lists.newArrayList(addedAttributes.keySet());
		Collections.reverse(entities);

		for (String entityName : entities)
		{
			for (AttributeMetaData attribute : addedAttributes.get(entityName))
			{
				targetCollection.dropAttributeMetaData(entityName, attribute.getName());
			}
		}
		return entities;
	}

	/**
	 * Drops added entities in the reverse order in which they were created.
	 */
	private void dropAddedEntities(ManageableCrudRepositoryCollection targetCollection, List<String> addedEntities)
	{
		// Rollback metadata, create table statements cannot be rolled back, we have to do it ourselves
		ArrayList<String> reversedEntities = new ArrayList<String>(addedEntities);
		Collections.reverse(reversedEntities);

		for (String entityName : reversedEntities)
		{
			targetCollection.dropEntityMetaData(entityName);
		}
	}

	/**
	 * Updates a repository with entities.
	 * 
	 * @param repo
	 *            the {@link Repository} to update
	 * @param entities
	 *            the entities to
	 * @param dbAction
	 *            {@link DatabaseAction} describing how to merge the existing entities
	 * @return number of updated entities
	 */
	public int update(CrudRepository repo, Iterable<? extends Entity> entities, DatabaseAction dbAction)
	{
		if (entities == null) return 0;

		String idAttributeName = repo.getEntityMetaData().getIdAttribute().getName();
		FieldType idDataType = repo.getEntityMetaData().getIdAttribute().getDataType();

		HugeSet<Object> existingIds = new HugeSet<Object>();
		HugeSet<Object> ids = new HugeSet<Object>();
		try
		{
			for (Entity entity : entities)
			{
				Object id = entity.get(idAttributeName);
				if (id != null)
				{
					ids.add(id);
				}
			}

			if (!ids.isEmpty())
			{
				// Check if the ids already exist
				if (repo.count() > 0)
				{
					int batchSize = 100;
					Query q = new QueryImpl();
					Iterator<Object> it = ids.iterator();
					int batchCount = 0;
					while (it.hasNext())
					{
						q.eq(idAttributeName, it.next());
						batchCount++;
						if (batchCount == batchSize || !it.hasNext())
						{
							for (Entity existing : repo.findAll(q))
							{
								existingIds.add(existing.getIdValue());
							}
							q = new QueryImpl();
							batchCount = 0;
						}
						else
						{
							q.or();
						}
					}
				}
			}

			int count = 0;
			switch (dbAction)
			{
				case ADD:
					if (!existingIds.isEmpty())
					{
						StringBuilder msg = new StringBuilder();
						msg.append("Trying to add existing ").append(repo.getName())
								.append(" entities as new insert: ");

						int i = 0;
						Iterator<?> it = existingIds.iterator();
						while (it.hasNext() && i < 5)
						{
							if (i > 0)
							{
								msg.append(",");
							}
							msg.append(it.next());
							i++;
						}

						if (it.hasNext())
						{
							msg.append(" and more.");
						}
						throw new MolgenisDataException(msg.toString());
					}
					count = repo.add(entities);
					break;

				case ADD_UPDATE_EXISTING:
					int batchSize = 1000;
					List<Entity> existingEntities = Lists.newArrayList();
					List<Entity> newEntities = Lists.newArrayList();

					Iterator<? extends Entity> it = entities.iterator();
					while (it.hasNext())
					{
						Entity entity = it.next();
						count++;
						Object id = idDataType.convert(entity.get(idAttributeName));
						if (existingIds.contains(id))
						{
							existingEntities.add(entity);
							if (existingEntities.size() == batchSize)
							{
								repo.update(existingEntities);
								existingEntities.clear();
							}
						}
						else
						{
							newEntities.add(entity);
							if (newEntities.size() == batchSize)
							{
								repo.add(newEntities);
								newEntities.clear();
							}
						}
					}

					if (!existingEntities.isEmpty())
					{
						repo.update(existingEntities);
					}

					if (!newEntities.isEmpty())
					{
						repo.add(newEntities);
					}
					break;

				case UPDATE:
					int errorCount = 0;
					StringBuilder msg = new StringBuilder();
					msg.append("Trying to update not exsisting ").append(repo.getName()).append(" entities:");

					for (Entity entity : entities)
					{
						count++;
						Object id = idDataType.convert(entity.get(idAttributeName));
						if (!existingIds.contains(id))
						{
							if (++errorCount == 6)
							{
								break;
							}

							if (errorCount > 0)
							{
								msg.append(", ");
							}
							msg.append(id);
						}
					}

					if (errorCount > 0)
					{
						if (errorCount == 6)
						{
							msg.append(" and more.");
						}
						throw new MolgenisDataException(msg.toString());
					}
					repo.update(entities);
					break;

				default:
					break;

			}

			return count;
		}
		finally
		{
			IOUtils.closeQuietly(existingIds);
			IOUtils.closeQuietly(ids);
		}
	}

}