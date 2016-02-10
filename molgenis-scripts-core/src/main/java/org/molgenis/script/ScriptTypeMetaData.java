package org.molgenis.script;

import static org.molgenis.data.EntityMetaData.AttributeRole.ROLE_ID;

import org.molgenis.data.support.SystemEntityMetaData;
import org.springframework.stereotype.Component;

@Component
public class ScriptTypeMetaData extends SystemEntityMetaData
{
	public ScriptTypeMetaData()
	{
		super(ScriptType.ENTITY_NAME, ScriptType.class);
		addAttribute(ScriptParameter.NAME, ROLE_ID).setNillable(false);
	}
}
