// @flow

export type GrantedAuthoritySid = {
  authority: string
}

export type PrincipalSid = {
  username: string
}

export type Sid = GrantedAuthoritySid | PrincipalSid

export type Role = {
  id?: string,
  label: string,
  description ?: string
}

export type EntityType = {
  id: string,
  label?: string,
  description?: string
}

export type Permission = string

export type ACE = {
  permissions: Array<Permission>,
  granting: boolean,
  securityId: Sid
}

export type EntityIdentity = {
  entityTypeId: string,
  entityId: string
}

export type ACL = {
  entityIdentity: EntityIdentity,
  owner: PrincipalSid,
  entries: Array<ACE>,
  parent?: ACL
}

export type Row = {
  acl: ACL,
  entityId: string,
  entityLabel: string
}

export type SidType = 'role' | 'user'

export type State = {
  me: PrincipalSid,
  roles: Array<Role>,
  selectedSid: ?string,
  users: ?Array<string>,
  groups: ?Array<string>,
  permissions: Array<string>,
  sidType: SidType,
  selectedEntityTypeId: ?string,
  entityTypes: Array<EntityType>,
  rows: Array<Row>,
  filter: ?string,
  doCreateRole: ?boolean,
  doUpdateRole: ?boolean,
  acl: ?ACL
}
