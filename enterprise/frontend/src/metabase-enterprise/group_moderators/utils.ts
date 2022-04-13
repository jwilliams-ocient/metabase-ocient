import { UserWithGroupManagerPermission } from "./types/user";

export const canAccessPeople = (user?: UserWithGroupManagerPermission) =>
  user?.permissions?.is_group_manager ?? false;
