<#--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

<@section title="${uiLabelMap.ProductIssueInventoryItemsToShipment}: [${shipmentId!}]">
    <@table type="data-list" cellspacing="0" cellpadding="2" class="basic-table hover-bar">
     <@thead>
      <@tr class="header-row">
        <@th>${uiLabelMap.CommonReturn} ${uiLabelMap.CommonDescription}</@th>
        <@th>${uiLabelMap.ProductProduct}</@th>
        <@th>${uiLabelMap.OrderReturnQty}</@th>
        <@th>${uiLabelMap.ProductShipmentQty}</@th>
        <@th>${uiLabelMap.ProductTotIssuedQuantity}</@th>
        <@th></@th>
        <@th>${uiLabelMap.CommonQty} ${uiLabelMap.CommonNot} ${uiLabelMap.ManufacturingIssuedQuantity}</@th>
        <@th>${uiLabelMap.ProductInventoryItemId} ${uiLabelMap.CommonQty} ${uiLabelMap.CommonSubmit}</@th>
      </@tr>
      </@thead>
      <#list items as item>
        <@tr>
          <@td><a href="/ordermgr/control/returnMain?returnId=${item.returnId}" class="${styles.button_default!}">${item.returnId}</a> [${item.returnItemSeqId}]</@td>
          <@td><a href="/catalog/control/EditProductInventoryItems?productId=${item.productId}" class="${styles.button_default!}">${item.productId}</a> ${item.internalName!}</@td>
          <@td>${item.returnQuantity}</@td>
          <@td>${item.shipmentItemQty}</@td>
          <@td>${item.totalQtyIssued}</@td>
          <@td>
            <#if item.issuedItems?has_content>
              <#list item.issuedItems as issuedItem>
                <div><a href="/facility/control/EditInventoryItem?inventoryItemId=${issuedItem.inventoryItemId}" class="${styles.button_default!}">${issuedItem.inventoryItemId}</a> ${issuedItem.quantity}</div>
              </#list>
            </#if>
          </@td>
          <@td>${item.qtyStillNeedToBeIssued}</@td>
          <#if (item.shipmentItemQty > item.totalQtyIssued)>
            <@td>
              <div>
                <form name="issueInventoryItemToShipment_${item_index}" action="<@ofbizUrl>issueInventoryItemToShipment</@ofbizUrl>" method="post">
                  <input type="hidden" name="shipmentId" value="${shipmentId}"/>
                  <input type="hidden" name="shipmentItemSeqId" value="${item.shipmentItemSeqId}"/>
                  <input type="hidden" name="totalIssuedQty" value="${item.totalQtyIssued}"/>
                  <span>
                    <@htmlTemplate.lookupField formName="issueInventoryItemToShipment_${item_index}" name="inventoryItemId" id="inventoryItemId" fieldFormName="LookupInventoryItem?orderId=${item.orderId}&amp;partyId=${item.partyId}&amp;productId=${item.productId}"/>
                  </span>
                  <input type="text" size="5" name="quantity"/>
                  <input type="submit" value="${uiLabelMap.CommonSubmit}" class="smallSubmit"/>
                </form>
              </div>
            </@td>
          </#if>
        </@tr>
      </#list>
    </@table>
</@section>
