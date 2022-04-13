import React from "react";
import PropTypes from "prop-types";
import { t } from "ttag";

import { isAdminGroup, isDefaultGroup } from "metabase/lib/groups";
import Icon from "metabase/components/Icon";
import AdminEmptyText from "metabase/components/AdminEmptyText";
import AdminContentTable from "metabase/components/AdminContentTable";
import PaginationControls from "metabase/components/PaginationControls";

import User from "metabase/entities/users";

import AddMemberRow from "../AddMemberRow";
import { Group, Member, User as IUser } from "metabase-types/api";
import { PLUGIN_GROUP_MODERATORS } from "metabase/plugins";

interface GroupMembersTableProps {
  group: Group;
  members: Member[];
  currentUser: Partial<IUser>;
  users: IUser[];
  showAddUser: any;
  text: string;
  selectedUsers: IUser[];
  onAddUserCancel: () => void;
  onAddUserDone: () => void;
  onAddUserTextChange: (value: string) => void;
  onUserSuggestionAccepted: (user: IUser) => void;
  onRemoveUserClicked: (user: IUser) => void;
  onRemoveUserFromSelection: (user: IUser) => void;
  onMembershipUpdate: (user: Member) => void;
}

export default function GroupMembersTable({
  group,
  members,
  currentUser: { id: currentUserId } = {},
  users,
  showAddUser,
  text,
  selectedUsers,
  onAddUserCancel,
  onAddUserDone,
  onAddUserTextChange,
  onUserSuggestionAccepted,
  onRemoveUserClicked,
  onRemoveUserFromSelection,
  onMembershipUpdate,
}: GroupMembersTableProps) {
  // you can't remove people from Default and you can't remove the last user from Admin
  const isCurrentUser = ({ id }: Partial<IUser>) => id === currentUserId;
  const canRemove = (user: IUser) =>
    !isDefaultGroup(group) && !(isAdminGroup(group) && isCurrentUser(user));

  const entityQuery = { group_id: group.id };

  return (
    <User.ListLoader pageSize={25} entityQuery={entityQuery}>
      {({
        list,
        page,
        pageSize,
        onNextPage,
        onPreviousPage,
        reload,
      }: {
        list: IUser[];
        page: number;
        pageSize: number;
        onNextPage: () => void;
        onPreviousPage: () => void;
        reload: () => void;
      }) => {
        const hasMembers = members.length !== 0;

        const handleAddUser = async () => {
          await onAddUserDone();
          reload();
        };

        const handleRemoveUser = async (user: IUser) => {
          await onRemoveUserClicked(user);
          reload();
        };

        const columnTitles = [
          t`Name`,
          PLUGIN_GROUP_MODERATORS.UserTypeCell ? t`Type` : null,
          t`Email`,
        ].filter(Boolean);

        return (
          <React.Fragment>
            <AdminContentTable columnTitles={columnTitles}>
              {showAddUser && (
                <AddMemberRow
                  users={users}
                  text={text}
                  selectedUsers={selectedUsers}
                  onCancel={onAddUserCancel}
                  onDone={handleAddUser}
                  onTextChange={onAddUserTextChange}
                  onSuggestionAccepted={onUserSuggestionAccepted}
                  onRemoveUserFromSelection={onRemoveUserFromSelection}
                />
              )}
              {list.map((user, index) => (
                <UserRow
                  key={index}
                  user={user}
                  membership={members.find(
                    membership => membership.user_id === user.id,
                  )}
                  canRemove={canRemove(user)}
                  onRemoveUserClicked={handleRemoveUser}
                  onMembershipUpdate={onMembershipUpdate}
                />
              ))}
            </AdminContentTable>
            {hasMembers && (
              <div className="flex align-center justify-end p2">
                <PaginationControls
                  page={page}
                  pageSize={pageSize}
                  itemsLength={list.length}
                  total={members.length}
                  onNextPage={onNextPage}
                  onPreviousPage={onPreviousPage}
                />
              </div>
            )}
            {!hasMembers && (
              <div className="mt4 pt4 flex layout-centered">
                <AdminEmptyText
                  message={t`A group is only as good as its members.`}
                />
              </div>
            )}
          </React.Fragment>
        );
      }}
    </User.ListLoader>
  );
}

interface UserRowProps {
  user: IUser;
  canRemove: boolean;
  onRemoveUserClicked: (user: IUser) => void;
  onMembershipUpdate: (membership: Member) => void;
  membership?: Member;
}

const UserRow = ({
  user,
  canRemove,
  onRemoveUserClicked,
  onMembershipUpdate,
  membership,
}: UserRowProps) => {
  return (
    <tr>
      <td>{user.first_name + " " + user.last_name}</td>
      {membership && PLUGIN_GROUP_MODERATORS.UserTypeCell && (
        <PLUGIN_GROUP_MODERATORS.UserTypeCell
          membership={membership}
          onMembershipUpdate={onMembershipUpdate}
        />
      )}
      <td>{user.email}</td>
      {canRemove ? (
        <td
          className="text-right cursor-pointer"
          onClick={() => onRemoveUserClicked(user)}
        >
          <Icon name="close" className="text-light" size={16} />
        </td>
      ) : null}
    </tr>
  );
};

UserRow.propTypes = {
  user: PropTypes.object.isRequired,
  canRemove: PropTypes.bool.isRequired,
  onRemoveUserClicked: PropTypes.func.isRequired,
};
