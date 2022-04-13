import { Member } from "metabase-types/api";

export interface MemberWithGroupManagerPermission extends Member {
  is_group_manager?: boolean;
}
