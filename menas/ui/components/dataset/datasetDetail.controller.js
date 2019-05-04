/*
 * Copyright 2018-2019 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
sap.ui.define([
  "sap/ui/core/mvc/Controller",
  "sap/ui/core/Fragment",
  "sap/m/MessageBox"
], function (Controller, Fragment, MessageBox) {
  "use strict";

  return Controller.extend("components.dataset.datasetDetail", {

    /**
     * Called when a controller is instantiated and its View controls (if
     * available) are already created. Can be used to modify the View before it
     * is displayed, to bind event handlers and do other one-time
     * initialization.
     *
     * @memberOf components.dataset.datasetDetail
     */
    onInit: function () {
      this._model = sap.ui.getCore().getModel();
      this._router = sap.ui.core.UIComponent.getRouterFor(this);
      this._router.getRoute("datasets").attachMatched(function (oEvent) {
        let args = oEvent.getParameter("arguments");
        this.routeMatched(args);
      }, this);

      let cont = sap.ui.controller("components.dataset.conformanceRule.upsert", true);
      this._upsertConformanceRuleDialog = sap.ui.xmlfragment("components.dataset.conformanceRule.upsert", cont);

      this._editFragment = new AddDatasetFragment(this, Fragment.load).getEdit();
    },

    onEditPress: function () {
      this._editFragment.onPress();
    },

    onDatasetSubmit: function () {
      this._editFragment.submit();
    },

    onDatasetCancel: function () {
      this._editFragment.cancel();
    },

    datasetNameChange: function () {
      this._editFragment.onNameChange();
    },

    onSchemaSelect: function (oEv) {
      this._editFragment.onSchemaSelect(oEv)
    },

    onAddConformanceRulePress: function () {
      this._model.setProperty("/newRule", {
        title: "Add",
        isEdit: false,
      });

      const rules = this._model.getProperty("/currentDataset/conformance");
      this._setRuleDialogModel(rules);

      this._upsertConformanceRuleDialog.open();
    },

    _setRuleDialogModel: function(rules) {
      const currentDataset = this._model.getProperty("/currentDataset");
      SchemaService.getSchemaVersionPromise(currentDataset.schemaName, currentDataset.schemaVersion)
        .then(schema => {
          schema.fields = SchemaManager.updateTransitiveSchema(schema.fields, rules);
          this._upsertConformanceRuleDialog.setModel(new sap.ui.model.json.JSONModel(schema), "schema");
        });
    },

    onRuleMenuAction: function (oEv) {
      let sAction = oEv.getParameter("item").data("action");
      let sBindPath = oEv.getParameter("item").getBindingContext().getPath();

      if (sAction === "edit") {
        let old = this._model.getProperty(sBindPath);
        this.fetchSchema();
        this._model.setProperty("/newRule", {
          ...$.extend(true, {}, old),
          title: "Edit",
          isEdit: true,
        });

        const rules = this._model.getProperty("/currentDataset/conformance").slice(0, old.order);
        this._setRuleDialogModel(rules);

        this._upsertConformanceRuleDialog.open();
      } else if (sAction === "delete") {
        sap.m.MessageBox.confirm("Are you sure you want to delete the conformance rule?", {
          actions: [sap.m.MessageBox.Action.YES, sap.m.MessageBox.Action.NO],
          onClose: function (oResponse) {
            if (oResponse === sap.m.MessageBox.Action.YES) {
              let toks = sBindPath.split("/");
              let ruleIndex = parseInt(toks[toks.length - 1]);
              let currentDataset = this._model.getProperty("/currentDataset");
              let newDataset = RuleService.removeRule(currentDataset, ruleIndex);

              if (newDataset) {
                DatasetService.editDataset(newDataset);
              }
            }
          }.bind(this)
        });
      }
    },

    auditVersionPress: function (oEv) {
      let oSrc = oEv.getParameter("listItem");
      let oRef = oSrc.data("menasRef");
      this._router.navTo("datasets", {
        id: oRef.name,
        version: oRef.version
      });
    },

    toSchema: function (oEv) {
      let src = oEv.getSource();
      sap.ui.core.UIComponent.getRouterFor(this).navTo("schemas", {
        id: src.data("name"),
        version: src.data("version")
      })
    },

    toMappingTable: function (oEv) {
      let src = oEv.getSource();
      sap.ui.core.UIComponent.getRouterFor(this).navTo("mappingTables", {
        id: src.data("name"),
        version: src.data("version")
      })
    },

    toRun: function (oEv) {
      let src = oEv.getParameter("listItem");
      let datasetName = src.data("datasetName");
      let datasetVersion = src.data("datasetVersion");
      let runId = src.data("runId");

      this._router.navTo("runs", {
        dataset: datasetName,
        version: datasetVersion,
        id: runId
      });
    },

    fetchSchema: function (oEv) {
      let dataset = sap.ui.getCore().getModel().getProperty("/currentDataset");
      if (typeof (dataset.schema) === "undefined") {
        SchemaService.getSchemaVersion(dataset.schemaName, dataset.schemaVersion, "/currentDataset/schema")
      }
    },

    fetchRuns: function (oEv) {
      let dataset = sap.ui.getCore().getModel().getProperty("/currentDataset");
      RunService.getDatasetRuns(this.byId("Runs"), dataset.name, dataset.version);
    },

    mappingTableSelect: function (oEv) {
      let sMappingTableId = oEv.getParameter("selectedItem").getKey();
      MappingTableService.getAllMappingTableVersions(sMappingTableId, sap.ui.getCore().byId("schemaVersionSelect"))
    },

    tabSelect: function (oEv) {
      if (oEv.getParameter("selectedKey") === "schema") {
        this.fetchSchema();
      }
      if (oEv.getParameter("selectedKey") === "runs") {
        this.fetchRuns();
      }
    },

    onRemovePress: function (oEv) {
      let current = this._model.getProperty("/currentDataset");

      sap.m.MessageBox.show("This action will remove all versions of the dataset definition. \nAre you sure?", {
        icon: sap.m.MessageBox.Icon.WARNING,
        title: "Are you sure?",
        actions: [sap.m.MessageBox.Action.YES, sap.m.MessageBox.Action.NO],
        onClose: function (oAction) {
          if (oAction === "YES") {
            DatasetService.disableDataset(current.name)
          }
        }
      });
    },

    routeMatched: function (oParams) {
      if (Prop.get(oParams, "id") === undefined) {
        DatasetService.getFirstDataset()
      } else if (Prop.get(oParams, "version") === undefined) {
        DatasetService.getLatestDatasetVersion(oParams.id)
      } else {
        DatasetService.getDatasetVersion(oParams.id, oParams.version)
      }
      this.byId("datasetIconTabBar").setSelectedKey("info");
    },

    conformanceRuleFactory: function (sId, oContext) {
      let sFragmentName = "components.dataset.conformanceRule." + oContext.getProperty("_t") + ".display";
      if (oContext.getProperty("_t") === "MappingConformanceRule") {

        let oAttributeMappings = oContext.getProperty("attributeMappings");
        let aJoinConditions = [];
        for (let key in oAttributeMappings) {
          let mappingTableName = oContext.getProperty("mappingTable");
          let datasetName = this._model.getProperty("/currentDataset/name");
          aJoinConditions.push({
            mappingTableField: mappingTableName + "." + key,
            datasetField: datasetName + "." + oAttributeMappings[key]
          });
        }

        oContext.getObject().joinConditions = aJoinConditions;
      }

      return sap.ui.xmlfragment(sId, sFragmentName, this);
    },

  });
});