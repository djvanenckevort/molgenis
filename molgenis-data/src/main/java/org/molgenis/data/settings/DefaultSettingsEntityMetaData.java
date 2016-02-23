package org.molgenis.data.settings;

import static org.molgenis.data.EntityMetaData.AttributeRole.ROLE_ID;

import org.molgenis.data.AttributeMetaData;
import org.molgenis.data.DataService;
import org.molgenis.data.Entity;
import org.molgenis.data.support.MapEntity;
import org.molgenis.data.support.SystemEntityMetaData;
import org.molgenis.security.core.runas.RunAsSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.Transactional;

public abstract class DefaultSettingsEntityMetaData extends SystemEntityMetaData
		implements ApplicationListener<ContextRefreshedEvent>, Ordered
{
	public static final String ATTR_ID = "id";

	@Autowired
	private DataService dataService;

	@Autowired
	public SettingsEntityMeta settingsEntityMeta;

	public DefaultSettingsEntityMetaData(String id)
	{
		super(id);
		setExtends(settingsEntityMeta);
		setPackage(SettingsPackage.INSTANCE);
		addAttribute(ATTR_ID, ROLE_ID).setLabel("Id").setVisible(false);
	}

	@RunAsSystem
	public Entity getSettings()
	{
		return dataService.findOne(getName(), getSimpleName());
	}

	public static String getSettingsEntityName(String id)
	{
		return SettingsPackage.PACKAGE_NAME + '_' + id;
	}

	private Entity getDefaultSettings()
	{
		MapEntity mapEntity = new MapEntity(this);
		for (AttributeMetaData attr : this.getAtomicAttributes())
		{
			String defaultValue = attr.getDefaultValue();
			if (defaultValue != null)
			{
				mapEntity.set(attr.getName(), defaultValue);
			}
		}
		return mapEntity;
	}

	@Transactional
	@RunAsSystem
	@Override
	public void onApplicationEvent(ContextRefreshedEvent event)
	{
		Entity settingsEntity = getSettings();
		if (settingsEntity == null)
		{
			Entity defaultSettingsEntity = getDefaultSettings();
			defaultSettingsEntity.set(ATTR_ID, getSimpleName());
			dataService.add(getName(), defaultSettingsEntity);
		}
	}

	@Override
	public int getOrder()
	{
		return Ordered.HIGHEST_PRECEDENCE + 110;
	}
}
