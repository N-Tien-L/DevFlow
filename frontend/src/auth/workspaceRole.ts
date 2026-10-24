const roleLabels: Record<string, string> = {
  OWNER: 'Chủ sở hữu',
  ADMIN: 'Quản trị',
  MEMBER: 'Thành viên',
};

/** Vietnamese label for a workspace role; unknown roles fall back to the raw value. */
export function workspaceRoleLabel(role: string) {
  return roleLabels[role] ?? role;
}
