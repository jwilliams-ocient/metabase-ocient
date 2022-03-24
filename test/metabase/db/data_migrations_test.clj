(ns metabase.db.data-migrations-test
  "Tests to make sure the data migrations actually work as expected and don't break things. Shamefully, we have way less
  of these than we should... but that doesn't mean we can't write them for our new ones :)"
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [crypto.random :as crypto-random]
            [metabase.db.data-migrations :as migrations]
            [metabase.models :refer [Card Dashboard DashboardCard Setting]]
            [metabase.models.permissions-group :as group]
            [metabase.models.setting :as setting]
            [metabase.public-settings.premium-features-test :as premium-features-test]
            [metabase.test :as mt]
            [metabase.test.fixtures :as fixtures]
            [metabase.util :as u]
            [toucan.db :as db]))

(use-fixtures :once (fixtures/initialize :db))

(deftest fix-click-through-test
  (let [migrate (fn [card dash]
                  (:visualization_settings
                   (#'migrations/fix-click-through {:id                     1
                                                    :dashcard_visualization dash
                                                    :card_visualization     card})))]
    (testing "toplevel"
      (let [card {"some_setting:"       {"foo" 123}
                  "click_link_template" "http://example.com/{{col_name}}"
                  "click"               "link"}
            dash {"other_setting" {"bar" 123}}]
        (is (= {"other_setting"  {"bar" 123}
                "click_behavior" {"type"         "link"
                                  "linkType"     "url"
                                  "linkTemplate" "http://example.com/{{col_name}}"}}
               (migrate card dash)))))

    (testing "top level disabled"
      (let [card {"some_setting:"       {"foo" 123}
                  "click_link_template" "http://example.com/{{col_name}}"
                  "click"               "link"}
            dash {"other_setting"       {"bar" 123}
                  "click_link_template" "http://example.com/{{col_name}}"
                  "click"               "menu"}]
        ;;click: "menu" turned off the custom drill through so it's not migrated. Dropping click and click_link_template would be fine but isn't needed.
        (is (nil? (migrate card dash)))))
    (testing "column settings"
      (let [card {"some_setting" {"foo" 123}
                  "column_settings"
                  {"[\"ref\",[\"field-id\",1]]"
                   {"view_as"       "link"
                    "link_template" "http://example.com/{{id}}"
                    "link_text"     "here is my id: {{id}}"}}}
            dash {"other_setting" {"bar" 123}
                  "column_settings"
                  {"[\"ref\",[\"field-id\",1]]" {"fun_formatting" "foo"}
                   "[\"ref\",[\"field-id\",2]]" {"other_fun_formatting" 123}}}]
        (is (= {"other_setting" {"bar" 123}
                "column_settings"
                {"[\"ref\",[\"field-id\",1]]"
                 {"fun_formatting" "foo"
                  "click_behavior" {"type"             "link"
                                    "linkType"         "url"
                                    "linkTemplate"     "http://example.com/{{id}}"
                                    "linkTextTemplate" "here is my id: {{id}}"}}
                 "[\"ref\",[\"field-id\",2]]"
                 {"other_fun_formatting" 123}}}
               (migrate card dash)))))
    (testing "manually updated new behavior"
      (let [card {"some_setting"        {"foo" 123}
                  "click_link_template" "http://example.com/{{col_name}}"
                  "click"               "link"}
            dash {"other_setting"  {"bar" 123}
                  "click_behavior" {"type"         "link"
                                    "linkType"     "url"
                                    "linkTemplate" "http://example.com/{{other_col_name}}"}}]
        (is (nil? (migrate card dash)))))
    (testing "Manually updated to new behavior on Column"
      (let [card {"some_setting" {"foo" 123},
                  "column_settings"
                  {"[\"ref\",[\"field-id\",1]]"
                   {"view_as"                  "link"
                    "link_template"            "http://example.com/{{id}}"
                    "other_special_formatting" "currency"}
                   "[\"ref\",[\"field-id\",2]]"
                   {"view_as"              "link",
                    "link_template"        "http://example.com/{{something_else}}",
                    "other_fun_formatting" 0}}}
            dash {"other_setting" {"bar" 123}
                  "column_settings"
                  {"[\"ref\",[\"field-id\",1]]"
                   {"click_behavior"
                    {"type"         "link"
                     "linkType"     "url"
                     "linkTemplate" "http://example.com/{{id}}"}}
                   "[\"ref\",[\"field-id\",2]]"
                   {"other_fun_formatting" 123}}}]
        (is (= {"other_setting" {"bar" 123}
                "column_settings"
                {"[\"ref\",[\"field-id\",1]]"
                 {"click_behavior"
                  {"type"         "link",
                   "linkType"     "url",
                   "linkTemplate" "http://example.com/{{id}}"}}
                 "[\"ref\",[\"field-id\",2]]"
                 {"other_fun_formatting" 123,
                  "click_behavior"
                  {"type"         "link",
                   "linkType"     "url",
                   "linkTemplate" "http://example.com/{{something_else}}"}}}}
               (migrate card dash)))))
    (testing "If there is migration eligible on dash but also new style on dash, new style wins"
      (let [dash {"column_settings"
                  {"[\"ref\",[\"field-id\",4]]"
                   {"view_as"       "link"
                    "link_template" "http://old" ;; this stuff could be migrated
                    "link_text"     "old"
                    "column_title"  "column title"
                    "click_behavior"
                    {"type"             "link",
                     "linkType"         "url", ;; but there is already a new style and it wins
                     "linkTemplate"     "http://new",
                     "linkTextTemplate" "new"}}}}]
        ;; no change
        (is (nil? (migrate nil dash)))))
    (testing "flamber case"
      (let [card {"column_settings"
                  {"[\"ref\",[\"field-id\",4]]"
                   {"view_as"       "link"
                    "link_template" "http//localhost/?QCDT&{{CATEGORY}}"
                    "link_text"     "MyQCDT {{CATEGORY}}"
                    "column_title"  "QCDT Category"}
                   "[\"ref\",[\"field-id\",6]]"
                   {"view_as"       "link"
                    "column_title"  "QCDT Rating"
                    "link_text"     "Rating {{RATING}}"
                    "link_template" "http//localhost/?QCDT&{{RATING}}"
                    "prefix"        "prefix-"
                    "suffix"        "-suffix"}
                   "[\"ref\",[\"field-id\",5]]"
                   {"view_as"       nil
                    "link_text"     "QCDT was disabled"
                    "link_template" "http//localhost/?QCDT&{{TITLE}}"
                    "column_title"  "(QCDT disabled) Title"}}
                  "table.pivot_column" "CATEGORY"
                  "table.cell_column"  "PRICE"}
            dash {"table.cell_column"  "PRICE"
                  "table.pivot_column" "CATEGORY"
                  "column_settings"
                  {"[\"ref\",[\"field-id\",5]]"
                   {"view_as"       nil
                    "link_text"     "QCDT was disabled"
                    "link_template" "http//localhost/?QCDT&{{TITLE}}"
                    "column_title"  "(QCDT disabled) Title"}
                   "[\"ref\",[\"field-id\",4]]"
                   {"view_as"       "link"
                    "link_template" "http//localhost/?QCDT&{{CATEGORY}}"
                    "link_text"     "MyQCDT {{CATEGORY}}"
                    "column_title"  "QCDT Category"
                    "click_behavior"
                    {"type"             "link"
                     "linkType"         "url"
                     "linkTemplate"     "http//localhost/?CB&{{CATEGORY}}"
                     "linkTextTemplate" "MyCB {{CATEGORY}}"}}
                   "[\"ref\",[\"field-id\",6]]"
                   {"view_as"       "link"
                    "column_title"  "QCDT Rating"
                    "link_text"     "Rating {{RATING}}"
                    "link_template" "http//localhost/?QCDT&{{RATING}}"
                    "prefix"        "prefix-"
                    "suffix"        "-suffix"}}
                  "card.title"         "Table with QCDT - MANUALLY ADDED CB 37"}]
        (is (= {"card.title"         "Table with QCDT - MANUALLY ADDED CB 37"
                "column_settings"
                {"[\"ref\",[\"field-id\",4]]"
                 {"column_title"  "QCDT Category"
                  "view_as"       "link"
                  "link_template" "http//localhost/?QCDT&{{CATEGORY}}"
                  "link_text"     "MyQCDT {{CATEGORY}}"
                  "click_behavior"
                  {"type"             "link"
                   "linkType"         "url"
                   "linkTemplate"     "http//localhost/?CB&{{CATEGORY}}"
                   "linkTextTemplate" "MyCB {{CATEGORY}}"}}
                 "[\"ref\",[\"field-id\",5]]"
                 {"link_text"     "QCDT was disabled"
                  "column_title"  "(QCDT disabled) Title"
                  "link_template" "http//localhost/?QCDT&{{TITLE}}"}
                 "[\"ref\",[\"field-id\",6]]"
                 {"prefix"        "prefix-"
                  "suffix"        "-suffix"
                  "column_title"  "QCDT Rating"
                  "view_as"       "link"
                  "link_text"     "Rating {{RATING}}"
                  "link_template" "http//localhost/?QCDT&{{RATING}}"
                  "click_behavior"
                  {"type"             "link"
                   "linkType"         "url"
                   "linkTemplate"     "http//localhost/?QCDT&{{RATING}}"
                   "linkTextTemplate" "Rating {{RATING}}"}}}
                "table.cell_column"  "PRICE"
                "table.pivot_column" "CATEGORY"}
               (migrate card dash))))))
  (testing "general case"
    (let [card-vis              {"column_settings"
                                 {"[\"ref\",[\"field-id\",2]]"
                                  {"view_as"       "link",
                                   "link_template" "http://example.com/{{ID}}",
                                   "link_text"     "here's an id: {{ID}}"},
                                  "[\"ref\",[\"field-id\",6]]"
                                  {"view_as"       "link",
                                   "link_template" "http://example.com//{{id}}",
                                   "link_text"     "here is my id: {{id}}"}},
                                 "table.pivot_column"  "QUANTITY",
                                 "table.cell_column"   "DISCOUNT",
                                 "click"               "link",
                                 "click_link_template" "http://example.com/{{count}}",
                                 "graph.dimensions"    ["CREATED_AT"],
                                 "graph.metrics"       ["count"],
                                 "graph.show_values"   true}
          original-dashcard-vis {"click"            "link",
                                 "click_link_template"
                                 "http://localhost:3001/?year={{CREATED_AT}}&cat={{CATEGORY}}&count={{count}}",
                                 "graph.dimensions" ["CREATED_AT" "CATEGORY"],
                                 "graph.metrics"    ["count"]}
          fixed                 (#'migrations/fix-click-through {:id                     1,
                                                                 :card_visualization     card-vis
                                                                 :dashcard_visualization original-dashcard-vis})]
      (is (= {:id 1,
              :visualization_settings
              {"graph.dimensions"    ["CREATED_AT" "CATEGORY"],
               "graph.metrics"       ["count"],
               "click"               "link",
               "click_link_template" "http://localhost:3001/?year={{CREATED_AT}}&cat={{CATEGORY}}&count={{count}}",
               "click_behavior"
               {"type"         "link",
                "linkType"     "url",
                "linkTemplate" "http://localhost:3001/?year={{CREATED_AT}}&cat={{CATEGORY}}&count={{count}}"},
               "column_settings"
               ;; note none of this keywordizes keys in json parsing since these structures are gross as keywords
               {"[\"ref\",[\"field-id\",2]]"
                {"click_behavior"
                 {"type"             "link",
                  "linkType"         "url",
                  "linkTemplate"     "http://example.com/{{ID}}",
                  "linkTextTemplate" "here's an id: {{ID}}"}},
                "[\"ref\",[\"field-id\",6]]"
                {"click_behavior"
                 {"type"             "link",
                  "linkType"         "url",
                  "linkTemplate"     "http://example.com//{{id}}",
                  "linkTextTemplate" "here is my id: {{id}}"}}}}}
             fixed))
      (testing "won't fix if fix is already applied"
        ;; a customer got a custom script from flamber (for which this is making that fix available for everyone. See
        ;; #15014)
        (is (= nil (#'migrations/fix-click-through
                    {:id                     1
                     :card_visualization     card-vis
                     :dashcard_visualization (:visualization_settings fixed)}))))))
  (testing "ignores columns when `view_as` is null"
    (let [card-viz {"column_settings"
                    {"normal"
                     ;; this one is view_as link so we should get it
                     {"view_as"       "link",
                      "link_template" "dash",
                      "link_text"     "here's an id: {{ID}}"}
                     "null-view-as"
                     {"view_as"       nil
                      "link_template" "i should not be present",
                      "link_text"     "i should not be present"}}}
          dash-viz {}]
      (is (= ["normal"]
             (keys (get-in
                    (#'migrations/fix-click-through {:id                     1
                                                     :card_visualization     card-viz
                                                     :dashcard_visualization dash-viz})
                    [:visualization_settings "column_settings"])))))))

(deftest migrate-click-through-test
  (testing "Migrate old style click through behavior to new (#15014)"
    (let [card-vis     (json/generate-string
                        {"column_settings"
                         {"[\"ref\",[\"field-id\",2]]"
                          {"view_as"       "link",
                           "link_template" "http://example.com/{{ID}}",
                           "link_text"     "here's an id: {{ID}}"},
                          "[\"ref\",[\"field-id\",6]]"
                          {"view_as"       "link",
                           "link_template" "http://example.com//{{id}}",
                           "link_text"     "here is my id: {{id}}"}},
                         "table.pivot_column"  "QUANTITY",
                         "table.cell_column"   "DISCOUNT",
                         "click"               "link",
                         "click_link_template" "http://example.com/{{count}}",
                         "graph.dimensions"    ["CREATED_AT"],
                         "graph.metrics"       ["count"],
                         "graph.show_values"   true})
          dashcard-vis (json/generate-string
                        {"click"            "link",
                         "click_link_template"
                         "http://localhost:3001/?year={{CREATED_AT}}&cat={{CATEGORY}}&count={{count}}",
                         "graph.dimensions" ["CREATED_AT" "CATEGORY"],
                         "graph.metrics"    ["count"]})]
      (mt/with-temp* [Dashboard     [{dashboard-id :id}]
                      Card          [{card-id :id} {:visualization_settings card-vis}]
                      DashboardCard [{dashcard-id :id} {:dashboard_id           dashboard-id
                                                        :card_id                card-id
                                                        :visualization_settings dashcard-vis}]]
        (let [expected-settings {:graph.dimensions ["CREATED_AT" "CATEGORY"],
                                 :graph.metrics    ["count"],
                                 :click            "link",
                                 :click_link_template
                                 "http://localhost:3001/?year={{CREATED_AT}}&cat={{CATEGORY}}&count={{count}}"
                                 :click_behavior
                                 {:type         "link",
                                  :linkType     "url",
                                  :linkTemplate "http://localhost:3001/?year={{CREATED_AT}}&cat={{CATEGORY}}&count={{count}}"},
                                 :column_settings
                                 ;; the model keywordizes the json parsing yielding this monstrosity below
                                 {"[\"ref\",[\"field\",2,null]]"
                                  {:click_behavior
                                   {:type             "link",
                                    :linkType         "url",
                                    :linkTemplate     "http://example.com/{{ID}}",
                                    :linkTextTemplate "here's an id: {{ID}}"}},
                                  "[\"ref\",[\"field\",6,null]]"
                                  {:click_behavior
                                   {:type             "link",
                                    :linkType         "url",
                                    :linkTemplate     "http://example.com//{{id}}",
                                    :linkTextTemplate "here is my id: {{id}}"}}}}
              get-settings!     #(:visualization_settings (db/select-one DashboardCard :id dashcard-id))]
          (#'migrations/migrate-click-through)
          (is (= expected-settings (get-settings!)))
          (testing "And it is idempotent"
            (#'migrations/migrate-click-through)
            (is (= expected-settings (get-settings!)))))))))

(defn- do-with-temporary-setting-value
  "Temporarily update the value of a setting, execute `thunk`, then re-establish the original value"
  [setting-k value thunk]
  (assert (and (string? setting-k) (string? value))
          (format "key and value should be string, got %s and %s" (type setting-k) (type value)))
  (let [original-value (db/select-one-field :value Setting :key setting-k)]
    (try
     (if original-value
       (db/update! Setting setting-k :value value)
       (db/insert! Setting :key setting-k :value value))
     (thunk)
     (finally
       (if original-value
         (db/update! Setting setting-k :value original-value)
         (db/delete! Setting :key setting-k))))))

(defmacro ^:private with-temporary-raw-setting-values
  "Temporarily write settings into Setting table, run body, re-establish the original settings.
  Note that this will not bind these settings to cache so using `setting/get` might not return the
  temporary values"
  [[setting-k value & more :as bindings] & body]
  (assert (even? (count bindings)) "mismatched setting/value pairs: is each setting name followed by a value?")
  (if (empty? bindings)
    `(do ~@body)
    `(do-with-temporary-setting-value ~(name setting-k) ~value
       (fn []
         (with-temporary-raw-setting-values ~more
           ~@body)))))

(defn- call-with-ldap-and-sso-configured [ldap-group-mapping sso-group-mapping f]
  (premium-features-test/with-premium-features #{:sso}
    (with-temporary-raw-setting-values
      [ldap-enabled                       "true"
       ldap-host                          "http://localhost:8888"
       ldap-user-base                     "dc=metabase,dc=com"
       ldap-group-mappings                (json/generate-string ldap-group-mapping)
       ldap-sync-admin-group              "false"
       jwt-enabled                        "true"
       jwt-identity-provider-uri          "http://test.idp.metabase.com"
       jwt-shared-secret                  (crypto-random/hex 32)
       jwt-group-mappings                 (json/generate-string sso-group-mapping)
       saml-enabled                       "true"
       saml-identity-provider-uri         "http://test.idp.metabase.com"
       saml-identity-provider-certificate (slurp "test_resources/sso/auth0-public-idp")
       saml-group-mappings                (json/generate-string sso-group-mapping)]
      (f))))

(defmacro ^:private with-ldap-and-sso-configured
  "Run body with ldap and SSO configured, in which SSO will only be configured if enterprise is available"
  [ldap-group-mapping sso-group-mapping & body]
  (binding [setting/*allow-retired-setting-names* true]
    `(call-with-ldap-and-sso-configured ~ldap-group-mapping ~sso-group-mapping (fn [] ~@body))))

(defn- get-json-setting
  [setting-k]
  (json/parse-string (db/select-one-field :value Setting :key (name setting-k))))

;; The `remove-admin-from-group-mapping-if-needed` migration is designed to run in OSS version
;; even though it might make changes to some enterprise-only settings.
;; In order to write tests to make sure it checks the behavior in both OSS and EE,
;; we can't use [[metabase.models.setting/get]] and [[metabase.test.util/with-temporary-setting-values]]
;; because they require all settings are defined.
;; That's why we use a set of helper functions to access raw setting to perform the tests
(deftest migrate-remove-admin-from-group-mapping-if-needed-test
  (let [admin-group-id        (u/the-id (group/admin))
        sso-group-mapping     {"group-mapping-a" [admin-group-id (+ 1 admin-group-id)]
                               "group-mapping-b" [admin-group-id (+ 1 admin-group-id) (+ 2 admin-group-id)]}
        ldap-group-mapping    {"dc=metabase,dc=com" [admin-group-id (+ 1 admin-group-id)]}
        sso-expected-mapping  {"group-mapping-a" [(+ 1 admin-group-id)]
                               "group-mapping-b" [(+ 1 admin-group-id) (+ 2 admin-group-id)]}
        ldap-expected-mapping {"dc=metabase,dc=com" [(+ 1 admin-group-id)]}]

    (testing "Remove admin from group mapping for LDAP, SAML, JWT if they are enabled"
      (with-ldap-and-sso-configured ldap-group-mapping sso-group-mapping
        (#'migrations/migrate-remove-admin-from-group-mapping-if-needed)
        (is (= ldap-expected-mapping (get-json-setting :ldap-group-mappings)))
        (is (= sso-expected-mapping (get-json-setting :jwt-group-mappings)))
        (is (= sso-expected-mapping (get-json-setting :saml-group-mappings)))))

    (testing "Does not remove admin from group mapping for LDAP, SAML, JWT if they are disabled"
      (with-ldap-and-sso-configured ldap-group-mapping sso-group-mapping
        (with-temporary-raw-setting-values
          [ldap-enabled "false"
           saml-enabled "false"
           jwt-enabled  "false"]
          (#'migrations/migrate-remove-admin-from-group-mapping-if-needed)
          (is (= ldap-group-mapping (get-json-setting :ldap-group-mappings)))
          (is (= sso-group-mapping (get-json-setting :jwt-group-mappings)))
          (is (= sso-group-mapping (get-json-setting :saml-group-mappings))))))

    (testing "Don't remove admin group if `ldap-sync-admin-group` is enabled"
      (with-ldap-and-sso-configured ldap-group-mapping nil
        (with-temporary-raw-setting-values
          [ldap-sync-admin-group "true"]
          (#'migrations/migrate-remove-admin-from-group-mapping-if-needed)
          (is (= ldap-group-mapping (get-json-setting :ldap-group-mappings))))))))
