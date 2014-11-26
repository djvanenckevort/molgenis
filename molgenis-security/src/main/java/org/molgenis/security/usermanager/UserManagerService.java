package org.molgenis.security.usermanager;

import java.util.List;

import org.molgenis.auth.MolgenisGroup;

public interface UserManagerService
{
	List<MolgenisUserViewData> getAllMolgenisUsers();

	void setActivationUser(Integer userId, Boolean active);

	void setActivationGroup(Integer groupId, Boolean active);

	List<MolgenisGroup> getAllMolgenisGroups();

	List<MolgenisGroup> getGroupsWhereUserIsMember(Integer userId);

	List<MolgenisGroup> getGroupsWhereUserIsNotMember(Integer userId);

	List<MolgenisUserViewData> getUsersMemberInGroup(Integer groupId);

	void addUserToGroup(Integer molgenisGroupId, Integer molgenisUserId);

	void removeUserFromGroup(Integer molgenisGroupId, Integer molgenisUserId);
}
