/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.order.shoppingcart.shipping;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.order.shoppingcart.ShoppingCart;
import org.ofbiz.order.shoppingcart.product.ProductPromoWorker;
import org.ofbiz.product.store.ProductStoreWorker;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

public class ShippingEstimateWrapper {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());

    // SCIPIO: WARN: do not store a ShoppingCart reference in this object.

    // SCIPIO: 2018-11-09: All fields now final.
    protected final Delegator delegator;
    protected final LocalDispatcher dispatcher;

    protected final Map<GenericValue, BigDecimal> shippingEstimates;
    protected final List<GenericValue> shippingMethods;

    protected final GenericValue shippingAddress;
    protected final Map<String, BigDecimal> shippableItemFeatures;
    protected final List<BigDecimal> shippableItemSizes;
    protected final List<Map<String, Object>> shippableItemInfo;
    protected final String productStoreId;
    protected final BigDecimal shippableQuantity;
    protected final BigDecimal shippableWeight;
    protected final BigDecimal shippableTotal;
    protected final String partyId;
    protected final String supplierPartyId;

    protected final Locale locale; // SCIPIO: 2018-11-09: Added locale
    protected final List<GenericValue> validShippingMethods; // SCIPIO: 2018-11-09
    protected final boolean allowMissingShipEstimates; // SCIPIO
    protected final Map<String, BigDecimal> validShippingMethodShippingPromos; //SCIPIO: 2.1.0:

    public static ShippingEstimateWrapper getWrapper(LocalDispatcher dispatcher, ShoppingCart cart, int shipGroup) {
        return new ShippingEstimateWrapper(dispatcher, cart, shipGroup);
    }

    public ShippingEstimateWrapper(LocalDispatcher dispatcher, ShoppingCart cart, int shipGroup) {
        this.dispatcher = dispatcher;
        this.delegator = cart.getDelegator();

        this.shippableItemFeatures = cart.getFeatureIdQtyMap(shipGroup);
        this.shippableItemSizes = cart.getShippableSizes(shipGroup);
        this.shippableItemInfo = cart.getShippableItemInfo(shipGroup);
        this.shippableQuantity = cart.getShippableQuantity(shipGroup);
        this.shippableWeight = cart.getShippableWeight(shipGroup);
        this.shippableTotal = cart.getShippableTotal(shipGroup);
        this.shippingAddress = cart.getShippingAddress(shipGroup);
        this.productStoreId = cart.getProductStoreId();
        this.partyId = cart.getPartyId();
        this.supplierPartyId = cart.getSupplierPartyId(shipGroup);
        
        this.locale = cart.getLocale();
        this.allowMissingShipEstimates = cart.isAllowMissingShipEstimates();

        List<GenericValue> shippingMethods = this.loadShippingMethods(); // SCIPIO: Locals
        Map<GenericValue, BigDecimal> shippingEstimates = this.loadEstimates(shippingMethods);
        this.shippingMethods = shippingMethods;
        this.shippingEstimates = shippingEstimates;
        
        List<GenericValue> validShippingMethods = new ArrayList<>(shippingMethods.size());
        for(GenericValue shipMethod : shippingMethods) {
            if (isValidShippingMethodInternal(shipMethod)) {
                validShippingMethods.add(shipMethod);
            }
        }
        this.validShippingMethods = validShippingMethods;

        // SCIPIO: 2.1.0: Find PROMO_SHIP_CHARGE per carrier from validShippingMethods for informational purposes (ie: show to customer shipping promos)
        // This is a bit problematic as we can't rely only on cart adjustments as they may not applied yet. So we are kinda forced to check promo condition on our own when not present.
        // TODO: Ideally this should work also for PPIP_ORDER_SHIPTOTAL ProductPromoCond type, but that one doesn't really work as expected
        Map<String, BigDecimal> validShippingMethodShippingPromos = UtilMisc.newMap();
        try {
            GenericValue shippingPromoAction = EntityQuery.use(delegator).from("ProductPromoAction").where("productPromoActionEnumId", "PROMO_SHIP_CHARGE", "orderAdjustmentTypeId", "PROMOTION_ADJUSTMENT").queryFirst();
            if (UtilValidate.isNotEmpty(shippingPromoAction) && UtilValidate.isNotEmpty(shippingPromoAction.getBigDecimal("amount"))) {
                GenericValue shippingPromo = shippingPromoAction.getRelatedOne("ProductPromo", false);
                GenericValue shippingPromoStore = EntityUtil.getFirst(shippingPromo.getRelated("ProductStorePromoAppl", UtilMisc.toMap("productStoreId", cart.getProductStoreId()), null, false));
                if (UtilValidate.isNotEmpty(shippingPromoStore) && UtilValidate.isNotEmpty(EntityUtil.filterByDate(UtilMisc.toList(shippingPromoStore)))) {
                    List<GenericValue> productPromoConds = EntityQuery.use(delegator).from("ProductPromoCond")
                            .where("productPromoId", shippingPromoAction.getString("productPromoId"), "productPromoRuleId", shippingPromoAction.getString("productPromoRuleId")).queryList();
                    String carrierShipmentMethodAndParty = null;
                    GenericValue currentProductPromoShipAmount = null;
                    int adjustmetCartIndex = cart.getAdjustmentPromoIndex(shippingPromoAction.getString("productPromoId"));
                    if (adjustmetCartIndex > -1 && cart.getAdjustments().size() > adjustmetCartIndex) {
                        currentProductPromoShipAmount = cart.getAdjustment(adjustmetCartIndex);
                    }
                    for (GenericValue productPromoCond : productPromoConds) {
                        if (UtilValidate.isNotEmpty(currentProductPromoShipAmount) ||
                                ProductPromoWorker.checkCondition(productPromoCond, cart, delegator, dispatcher, UtilDateTime.nowTimestamp())) {
                            if (UtilValidate.isNotEmpty(productPromoCond.getString("otherValue"))) {
                                String otherValue = productPromoCond.getString("otherValue");
                                if (otherValue != null && otherValue.contains("@")) {
                                    carrierShipmentMethodAndParty = otherValue.substring(otherValue.indexOf('@') + 1) + "@" + otherValue.substring(0, otherValue.indexOf('@'));
                                }
                                break;
                            } else {
                                carrierShipmentMethodAndParty = "N@A";
                            }
                        }
                    }

                    if (UtilValidate.isNotEmpty(carrierShipmentMethodAndParty)) {
                        for (GenericValue carrierShipmentMethod : validShippingMethods) {
                            String currentCarrierShipmentMethodAndParty = carrierShipmentMethod.getString("shipmentMethodTypeId") + "@" + carrierShipmentMethod.getString("partyId");
                            if ((currentCarrierShipmentMethodAndParty.equals(carrierShipmentMethodAndParty) || carrierShipmentMethodAndParty.equals("N@A"))
                                    && UtilValidate.isNotEmpty(shippingPromoAction.getBigDecimal("amount"))) {
                                BigDecimal shipmentEstimateWithPromoApplied = getShippingEstimate(carrierShipmentMethod).multiply(shippingPromoAction.getBigDecimal("amount")).divide(new BigDecimal(100));
                                validShippingMethodShippingPromos.put(currentCarrierShipmentMethodAndParty, shipmentEstimateWithPromoApplied);
                            }
                        }
                    }
                }
            }
        } catch(Exception e){
            Debug.logError(e.getMessage(), module);
        }
        this.validShippingMethodShippingPromos = validShippingMethodShippingPromos;
    }

    protected List<GenericValue> loadShippingMethods() { // SCIPIO: Added return value
        try {
            return ProductStoreWorker.getAvailableStoreShippingMethods(delegator, productStoreId,
                    shippingAddress, shippableItemSizes, shippableItemFeatures, shippableWeight, shippableTotal);
        } catch (Throwable t) {
            Debug.logError(t, module);
        }
        // SCIPIO: code will crash on this
        //return null;
        return Collections.emptyList();
    }

    protected Map<GenericValue, BigDecimal> loadEstimates(List<GenericValue> shippingMethods) { // SCIPIO: Added return value
        Map<GenericValue, BigDecimal> shippingEstimates = new HashMap<>();
        if (shippingMethods != null) {
            for (GenericValue shipMethod : shippingMethods) {
                String shippingMethodTypeId = shipMethod.getString("shipmentMethodTypeId");
                String carrierRoleTypeId = shipMethod.getString("roleTypeId");
                String carrierPartyId = shipMethod.getString("partyId");
                String productStoreShipMethId = shipMethod.getString("productStoreShipMethId");
                String shippingCmId = shippingAddress != null ? shippingAddress.getString("contactMechId") : null;

                // SCIPIO: 2018-11-09: Added locale, allowMissingEstimates
                Map<String, Object> estimateMap = ShippingEvents.getShipGroupEstimate(dispatcher, delegator, locale, "SALES_ORDER",
                        shippingMethodTypeId, carrierPartyId, carrierRoleTypeId, shippingCmId, productStoreId,
                        supplierPartyId, shippableItemInfo, shippableWeight, shippableQuantity, shippableTotal, partyId, productStoreShipMethId,
                        allowMissingShipEstimates);

                if (ServiceUtil.isSuccess(estimateMap)) {
                    BigDecimal shippingTotal = (BigDecimal) estimateMap.get("shippingTotal");
                    shippingEstimates.put(shipMethod, shippingTotal);
                }
            }
        }
        return shippingEstimates;
    }

    /**
     * SCIPIO: Returns only valid shipping methods for selection for the current process.
     * ALIAS for {@link #getValidShippingMethods()}.
     * <p>
     * <strong>NOTE:</strong> As of 2018-11-09, this method only returns shipping methods
     * deemed "valid" for the current process. Prior to this, this used to return methods
     * even if they missed estimates and this is not allowed; to get that behavior again,
     * use {@link #getAllShippingMethods()}.
     * <p>
     * Largely depends on {@link #isAllowMissingShipEstimates()}.
     * <p>
     * Modified 2018-11-09.
     */
    public List<GenericValue> getShippingMethods() {
        //return shippingMethods;
        return validShippingMethods;
    }

    /**
     * SCIPIO: Returns only valid shipping methods for selection for the current process.
     * ALIAS for {@link #getShippingMethods()}.
     * <p>
     * Added 2018-11-09.
     */
    public List<GenericValue> getValidShippingMethods() {
        return validShippingMethods;
    }

    /**
     * SCIPIO: Returns all shipping methods for the store even if invalid for selection (e.g. bad estimates).
     * <p>
     * This implements the old behavior of {@link #getShippingMethods()} prior to 2018-11-09.
     * <p>
     * Added 2018-11-09.
     */
    public List<GenericValue> getAllShippingMethods() {
        return shippingMethods;
    }

    public Map<GenericValue, BigDecimal> getAllEstimates() {
        return shippingEstimates;
    }

    public BigDecimal getShippingEstimate(GenericValue storeCarrierShipMethod) {
        return shippingEstimates.get(storeCarrierShipMethod);
    }

    /**
     * SCIPIO: If true, {@link #getShippingMethods()} will return methods
     * even if they returned no valid estimates.
     */
    public boolean isAllowMissingShipEstimates() {
        return allowMissingShipEstimates;
    }

    /**
     * SCIPIO: Returns true if the shipping method is valid for selection.
     * Added 2018-11-09.
     */
    public boolean isValidShippingMethod(GenericValue storeCarrierShipMethod) {
        if (isAllowMissingShipEstimates()) {
            return shippingMethods.contains(storeCarrierShipMethod);
        } else {
            return isValidEstimate(getShippingEstimate(storeCarrierShipMethod), storeCarrierShipMethod);
        }
    }

    protected boolean isValidShippingMethodInternal(GenericValue storeCarrierShipMethod) { // same as isValidShippingMethod but assumes storeCarrierShipMethod part of store methods
        if (isAllowMissingShipEstimates()) {
            return true;
        } else {
            return isValidEstimate(getShippingEstimate(storeCarrierShipMethod), storeCarrierShipMethod);
        }
    }    
    
    /**
     * SCIPIO: Checks if the given ship method has a valid estimate.
     * NOTE: Does NOT necessarily imply the whole shipping method is valid for usage;
     * use {@link #isValidShippingMethod} for that.
     * Added 2018-11-09.
     */
    public boolean isValidEstimate(GenericValue storeCarrierShipMethod) {
        return isValidEstimate(getShippingEstimate(storeCarrierShipMethod), storeCarrierShipMethod);
    }

    /**
     * SCIPIO: isValidShippingEstimate.
     * Added 2018-11-09.
     */
    protected boolean isValidEstimate(BigDecimal estimate, GenericValue storeCarrierShipMethod) {
        if (!(estimate == null || estimate.compareTo(BigDecimal.ZERO) < 0)) { // Same logic as PayPalServices.payPalCheckoutUpdate
            return true;
        }
        return ("NO_SHIPPING".equals(storeCarrierShipMethod.get("shipmentMethodTypeId"))) && shippingEstimates.containsKey(storeCarrierShipMethod); // Special case
    }

    /**
     * SCIPIO: 2.1.0: Returns shipping promos applied to valid shipping methods per shippingMethodTypeId@carrierId, when they are present
     */
    public Map<String, BigDecimal> getValidShippingMethodShippingPromos() {
        return validShippingMethodShippingPromos;
    }
}
