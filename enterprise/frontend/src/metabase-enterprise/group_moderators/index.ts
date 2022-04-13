import { hasPremiumFeature } from "metabase-enterprise/settings";
import { NAV_PERMISSION_GUARD } from "metabase/nav/utils";
import { PLUGIN_GROUP_MODERATORS } from "metabase/plugins";
import { UserTypeCell } from "./components/UserTypeCell";
import { UserTypeToggle } from "./components/UserTypeToggle";
import { canAccessPeople } from "./utils";

if (hasPremiumFeature("advanced_permissions")) {
  NAV_PERMISSION_GUARD["people"] = canAccessPeople;

  PLUGIN_GROUP_MODERATORS.UserTypeCell = UserTypeCell;
  PLUGIN_GROUP_MODERATORS.UserTypeToggle = UserTypeToggle;
}
