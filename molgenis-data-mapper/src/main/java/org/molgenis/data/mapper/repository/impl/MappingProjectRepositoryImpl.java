package org.molgenis.data.mapper.repository.impl;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;

import org.molgenis.auth.MolgenisUser;
import org.molgenis.data.DataService;
import org.molgenis.data.Entity;
import org.molgenis.data.MolgenisDataException;
import org.molgenis.data.Query;
import org.molgenis.data.mapper.mapping.model.MappingProject;
import org.molgenis.data.mapper.mapping.model.MappingTarget;
import org.molgenis.data.mapper.meta.MappingProjectMetaData;
import org.molgenis.data.mapper.repository.MappingProjectRepository;
import org.molgenis.data.mapper.repository.MappingTargetRepository;
import org.molgenis.data.populate.IdGenerator;
import org.molgenis.data.support.DynamicEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.Lists;

public class MappingProjectRepositoryImpl implements MappingProjectRepository
{
	private final MappingTargetRepository mappingTargetRepository;
	private final DataService dataService;
	private final IdGenerator idGenerator;
	private final MappingProjectMetaData mappingProjectMetaData;

	@Autowired
	public MappingProjectRepositoryImpl(MappingTargetRepository mappingTargetRepository, DataService dataService,
			IdGenerator idGenerator, MappingProjectMetaData mappingProjectMetaData)
	{
		this.mappingTargetRepository = requireNonNull(mappingTargetRepository);
		this.dataService = requireNonNull(dataService);
		this.idGenerator = requireNonNull(idGenerator);
		this.mappingProjectMetaData = requireNonNull(mappingProjectMetaData);
	}

	@Override
	@Transactional
	public void add(MappingProject mappingProject)
	{
		if (mappingProject.getIdentifier() != null)
		{
			throw new MolgenisDataException("MappingProject already exists");
		}
		dataService.add(mappingProjectMetaData.getName(), toEntity(mappingProject));
	}

	@Override
	@Transactional
	public void update(MappingProject mappingProject)
	{
		MappingProject existing = getMappingProject(mappingProject.getIdentifier());
		if (existing == null)
		{
			throw new MolgenisDataException("MappingProject does not exist");
		}
		Entity mappingProjectEntity = toEntity(mappingProject);
		dataService.update(mappingProjectMetaData.getName(), mappingProjectEntity);
	}

	@Override
	public MappingProject getMappingProject(String identifier)
	{
		Entity mappingProjectEntity = dataService.findOneById(mappingProjectMetaData.getName(), identifier);
		if (mappingProjectEntity == null)
		{
			return null;
		}
		return toMappingProject(mappingProjectEntity);
	}

	@Override
	public List<MappingProject> getAllMappingProjects()
	{
		List<MappingProject> results = new ArrayList<>();
		dataService.findAll(mappingProjectMetaData.getName()).forEach(entity -> {
			results.add(toMappingProject(entity));
		});
		return results;
	}

	@Override
	public List<MappingProject> getMappingProjects(Query<Entity> q)
	{
		List<MappingProject> results = new ArrayList<>();
		dataService.findAll(mappingProjectMetaData.getName(), q).forEach(entity -> {
			results.add(toMappingProject(entity));
		});
		return results;
	}

	/**
	 * Creates a fully reconstructed MappingProject from an Entity retrieved from the repository.
	 *
	 * @param mappingProjectEntity
	 *            Entity with {@link MappingProjectMetaData} metadata
	 * @return fully reconstructed MappingProject
	 */
	private MappingProject toMappingProject(Entity mappingProjectEntity)
	{
		String identifier = mappingProjectEntity.getString(MappingProjectMetaData.IDENTIFIER);
		String name = mappingProjectEntity.getString(MappingProjectMetaData.NAME);
		MolgenisUser owner = mappingProjectEntity.getEntity(MappingProjectMetaData.OWNER, MolgenisUser.class);
		List<Entity> mappingTargetEntities = Lists
				.newArrayList(mappingProjectEntity.getEntities(MappingProjectMetaData.MAPPING_TARGETS));
		List<MappingTarget> mappingTargets = mappingTargetRepository.toMappingTargets(mappingTargetEntities);

		return new MappingProject(identifier, name, owner, mappingTargets);
	}

	/**
	 * Creates a new Entity for a MappingProject. Upserts the {@link MappingProject}'s {@link MappingTarget}s in the
	 * {@link #mappingTargetRepo}.
	 *
	 * @param mappingProject
	 *            the {@link MappingProject} used to create an Entity
	 * @return Entity filled with the data from the MappingProject
	 */
	private Entity toEntity(MappingProject mappingProject)
	{
		Entity result = new DynamicEntity(mappingProjectMetaData);
		if (mappingProject.getIdentifier() == null)
		{
			mappingProject.setIdentifier(idGenerator.generateId());
		}
		result.set(MappingProjectMetaData.IDENTIFIER, mappingProject.getIdentifier());
		result.set(MappingProjectMetaData.OWNER, mappingProject.getOwner());
		result.set(MappingProjectMetaData.NAME, mappingProject.getName());
		List<Entity> mappingTargetEntities = mappingTargetRepository.upsert(mappingProject.getMappingTargets());
		result.set(MappingProjectMetaData.MAPPING_TARGETS, mappingTargetEntities);
		return result;
	}

	@Override
	public void delete(String mappingProjectId)
	{
		MappingProject mappingProject = getMappingProject(mappingProjectId);
		if (mappingProject != null)
		{
			List<MappingTarget> mappingTargets = mappingProject.getMappingTargets();
			dataService.deleteById(mappingProjectMetaData.getName(), mappingProjectId);
			mappingTargetRepository.delete(mappingTargets);
		}
	}
}