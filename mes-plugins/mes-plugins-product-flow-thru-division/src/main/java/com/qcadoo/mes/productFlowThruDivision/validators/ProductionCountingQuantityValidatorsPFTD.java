/**
 * ***************************************************************************
 * Copyright (c) 2010 Qcadoo Limited
 * Project: Qcadoo Framework
 * Version: 1.4
 *
 * This file is part of Qcadoo.
 *
 * Qcadoo is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation; either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 * ***************************************************************************
 */
package com.qcadoo.mes.productFlowThruDivision.validators;

import org.springframework.stereotype.Service;

import com.qcadoo.mes.basicProductionCounting.constants.ProductionCountingQuantityFields;
import com.qcadoo.mes.basicProductionCounting.constants.ProductionCountingQuantityRole;
import com.qcadoo.mes.basicProductionCounting.constants.ProductionCountingQuantityTypeOfMaterial;
import com.qcadoo.mes.orders.constants.OrderFields;
import com.qcadoo.mes.orders.states.constants.OrderState;
import com.qcadoo.mes.productFlowThruDivision.constants.ProductionCountingQuantityFieldsPFTD;
import com.qcadoo.mes.productFlowThruDivision.constants.ProductionFlowComponent;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.Entity;

@Service
public class ProductionCountingQuantityValidatorsPFTD {

    private static final String L_QCADOO_VIEW_VALIDATE_FIELD_ERROR_MISSING = "qcadooView.validate.field.error.missing";

    public boolean checkRequiredFields(final DataDefinition dataDefinition, final Entity productionCountingQuantity) {
        String role = productionCountingQuantity.getStringField(ProductionCountingQuantityFields.ROLE);
        String typeOfMaterial = productionCountingQuantity.getStringField(ProductionCountingQuantityFields.TYPE_OF_MATERIAL);
        Entity order = productionCountingQuantity.getBelongsToField(ProductionCountingQuantityFields.ORDER);
        if (!order.getStringField(OrderFields.STATE).equals(OrderState.PENDING.getStringValue())) {
            if (ProductionCountingQuantityRole.USED.getStringValue().equals(role)
                    && ProductionCountingQuantityTypeOfMaterial.COMPONENT.getStringValue().equals(typeOfMaterial)
                    && productionCountingQuantity
                            .getBelongsToField(ProductionCountingQuantityFieldsPFTD.COMPONENTS_LOCATION) == null) {
                productionCountingQuantity.addError(
                        dataDefinition.getField(ProductionCountingQuantityFieldsPFTD.COMPONENTS_LOCATION),
                        L_QCADOO_VIEW_VALIDATE_FIELD_ERROR_MISSING);
            }
            if (ProductionCountingQuantityRole.PRODUCED.getStringValue().equals(role)
                    && (ProductionCountingQuantityTypeOfMaterial.FINAL_PRODUCT.getStringValue().equals(typeOfMaterial)
                        || ProductionCountingQuantityTypeOfMaterial.ADDITIONAL_FINAL_PRODUCT.getStringValue().equals(typeOfMaterial))
                    && productionCountingQuantity
                            .getBelongsToField(ProductionCountingQuantityFieldsPFTD.PRODUCTS_INPUT_LOCATION) == null) {
                productionCountingQuantity.addError(
                        dataDefinition.getField(ProductionCountingQuantityFieldsPFTD.PRODUCTS_INPUT_LOCATION),
                        L_QCADOO_VIEW_VALIDATE_FIELD_ERROR_MISSING);
            }
            if (ProductionCountingQuantityTypeOfMaterial.INTERMEDIATE.getStringValue().equals(typeOfMaterial)
                    && ProductionFlowComponent.WAREHOUSE.getStringValue().equals(
                            productionCountingQuantity.getStringField(ProductionCountingQuantityFieldsPFTD.PRODUCTION_FLOW))
                    && productionCountingQuantity
                            .getBelongsToField(ProductionCountingQuantityFieldsPFTD.PRODUCTS_FLOW_LOCATION) == null) {
                productionCountingQuantity.addError(
                        dataDefinition.getField(ProductionCountingQuantityFieldsPFTD.PRODUCTS_FLOW_LOCATION),
                        L_QCADOO_VIEW_VALIDATE_FIELD_ERROR_MISSING);
            }
            return productionCountingQuantity.isValid();
        }
        return checkComponentsWarehouses(productionCountingQuantity);
    }

    private boolean checkComponentsWarehouses(final Entity pcq) {
        Entity componentsLocation = pcq.getBelongsToField(ProductionCountingQuantityFieldsPFTD.COMPONENTS_LOCATION);
        Entity componentsOutputLocation = pcq.getBelongsToField(ProductionCountingQuantityFieldsPFTD.COMPONENTS_OUTPUT_LOCATION);
        if (componentsLocation != null && componentsOutputLocation != null
                && componentsLocation.getId().equals(componentsOutputLocation.getId())) {
            pcq.addGlobalError("technologies.technology.error.componentsLocationsAreSame", false);
            return false;
        }
        return true;
    }
}
