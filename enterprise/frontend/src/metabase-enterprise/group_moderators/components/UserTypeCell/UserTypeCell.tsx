import React from "react";
import { t } from "ttag";
import { UserTypeCellRoot } from "./UserTypeCell.styled";
import { UserTypeToggle } from "../UserTypeToggle";
import { MemberWithGroupManagerPermission } from "metabase-enterprise/group_moderators/types/member";

interface UserTypeCellProps {
  membership: MemberWithGroupManagerPermission;
  onMembershipUpdate: (user: MemberWithGroupManagerPermission) => void;
}
export const UserTypeCell = ({
  membership,
  onMembershipUpdate,
}: UserTypeCellProps) => {
  const handleTypeChange = (is_group_manager: boolean) => {
    onMembershipUpdate({
      ...membership,
      is_group_manager,
    });
  };

  return (
    <UserTypeCellRoot>
      {membership.is_group_manager ? t`Manager` : t`Member`}
      <UserTypeToggle
        isManager={membership.is_group_manager ?? false}
        onChange={handleTypeChange}
      />
    </UserTypeCellRoot>
  );
};
