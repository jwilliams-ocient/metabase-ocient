export type GroupId = number;

export type Member = {
  user_id: number;
  membership_id: number;
  email: string;
  first_name: string;
  last_name: string;
};

export type Group = {
  id: GroupId;
  members: Member[];
  name: string;
  member_count: number;
};
