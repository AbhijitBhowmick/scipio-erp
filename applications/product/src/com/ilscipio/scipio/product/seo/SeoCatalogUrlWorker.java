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
package com.ilscipio.scipio.product.seo;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.StringUtil;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilHttp;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.cache.UtilCache;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.product.catalog.CatalogWorker;
import org.ofbiz.product.category.CatalogUrlFilter.CatalogAltUrlBuilder;
import org.ofbiz.product.category.CatalogUrlServlet.CatalogUrlBuilder;
import org.ofbiz.product.category.CategoryContentWrapper;
import org.ofbiz.product.category.CategoryWorker;
import org.ofbiz.product.product.ProductContentWrapper;
import org.ofbiz.product.product.ProductWorker;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.webapp.FullWebappInfo;

import com.ilscipio.scipio.ce.util.SeoStringUtil;
import com.ilscipio.scipio.product.category.CatalogAltUrlSanitizer;
import com.ilscipio.scipio.product.category.CatalogUrlType;

/**
 * SCIPIO: SEO url building functions and callbacks.
 * <p>
 * Some parts adapted from the original <code>org.ofbiz.product.category.ftl.CatalogUrlSeoTransform</code>;
 * others re-done based on {@link org.ofbiz.product.category.CatalogUrlFilter}.
 * <p>
 * <strong>WARN:</strong> Do not call makeXxxUrl methods from this class from client code!
 * Client code that need java methods should use (which these plug into):
 * <ul>
 * <li>{@link org.ofbiz.product.category.CatalogUrlFilter#makeCatalogAltLink}</li>
 * <li>{@link org.ofbiz.product.category.CatalogUrlServlet#makeCatalogLink}</li>
 * </ul>
 * FIXME: makeXxxUrlPath methods do not respect useCache flag
 */
@SuppressWarnings("serial")
public class SeoCatalogUrlWorker implements Serializable {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());

    public static final String DEFAULT_CONFIG_RESOURCE = "SeoConfigUiLabels";

    public static final boolean DEBUG = false;

    // TODO: in production, these cache can be tweaked with non-soft refs, limits and expire time
    private static final UtilCache<String, PathPartMatches> productAltUrlPartInfoCache = UtilCache.createUtilCache("seo.filter.product.alturl.part", true);
    private static final UtilCache<String, PathPartMatches> categoryAltUrlPartInfoCache = UtilCache.createUtilCache("seo.filter.category.alturl.part", true);
    private static final UtilCache<String, String> productUrlCache = UtilCache.createUtilCache("seo.filter.product.url", true);
    private static final UtilCache<String, TrailCacheEntry> productTrailCache = UtilCache.createUtilCache("seo.filter.product.trails", true);
    private static final UtilCache<String, String> categoryUrlCache = UtilCache.createUtilCache("seo.filter.category.url", true);
    private static final UtilCache<String, TrailCacheEntry> categoryTrailCache = UtilCache.createUtilCache("seo.filter.category.trails", true);

    protected static class TrailCacheEntry implements Serializable {
        protected final Set<String> topCategoryIds;
        protected final List<List<String>> trails;
        protected TrailCacheEntry(Set<String> topCategoryIds, List<List<String>> trails) {
            this.topCategoryIds = topCategoryIds;
            this.trails = (trails != null) ? trails : Collections.emptyList();
        }
        public Set<String> getTopCategoryIds() { return topCategoryIds; }
        public List<List<String>> getTrails() { return trails; }
    }

    static {
        CatalogUrlBuilder.registerUrlBuilder("seo", BuilderFactory.getInstance());
        CatalogAltUrlBuilder.registerUrlBuilder("seo", BuilderFactory.getInstance());
    }

    public static void initStatic() {
        // formality
    }

    private static final class Instances {
        private static final SeoCatalogUrlWorker DEFAULT = new SeoCatalogUrlWorker();
    }

    // trying to avoid this if possible... if needed, has to be configurable
//    /**
//     * FIXME: unhardcode; could be per-store.
//     */
//    private static final List<String> DEFAULT_BROWSABLE_ROOTCATTYPES = UtilMisc.unmodifiableArrayList(
//            "PCCT_BROWSE_ROOT", "PCCT_PROMOTIONS", "PCCT_BEST_SELL"
//            );

    /*
     * *****************************************************
     * Fields
     * *****************************************************
     */

    public enum UrlType {
        PRODUCT,
        CATEGORY;
        // TODO?: FUTURE: CONTENT
    }

    protected SeoConfig config;

    // TODO: intended for later
    //protected final String webSiteId;
    //protected final String contextPath;

    protected final String configResourceName;

    // lazy load, these do not require sync
    protected LocalizedName productPathName = null;
    protected LocalizedName categoryPathName = null;

    // kludge for no multiple inheritance
    protected final SeoCatalogUrlBuilder catalogUrlBuilder;
    protected final SeoCatalogAltUrlBuilder catalogAltUrlBuilder;
    protected final CatalogAltUrlSanitizer catalogAltUrlSanitizer;

    /*
     * *****************************************************
     * Constructors and factories
     * *****************************************************
     */

    protected SeoCatalogUrlWorker(SeoConfig config) {
        this.config = config;
        this.configResourceName = DEFAULT_CONFIG_RESOURCE;
        this.catalogUrlBuilder = createCatalogUrlBuilder();
        this.catalogAltUrlBuilder = createCatalogAltUrlBuilder();
        this.catalogAltUrlSanitizer = createCatalogAltUrlSanitizer();
    }

    protected SeoCatalogUrlWorker() {
        this(SeoConfig.getCommonConfig());
    }

    public static class Factory<T extends SeoCatalogUrlWorker> implements Serializable {
        protected static final Factory<SeoCatalogUrlWorker> DEFAULT = new Factory<>();
        public static Factory<SeoCatalogUrlWorker> getDefault() { return DEFAULT; }
        public SeoCatalogUrlWorker getUrlWorker(SeoConfig config) {
            return new SeoCatalogUrlWorker(config);
        }
    }

    protected SeoCatalogUrlBuilder createCatalogUrlBuilder() {
        return new SeoCatalogUrlBuilder();
    }

    protected SeoCatalogAltUrlBuilder createCatalogAltUrlBuilder() {
        return new SeoCatalogAltUrlBuilder();
    }

    protected CatalogAltUrlSanitizer createCatalogAltUrlSanitizer() {
        return new SeoCatalogAltUrlSanitizer();
    }

    /**
     * Returns an instance with possible website-specific configuration.
     */
    public static SeoCatalogUrlWorker getInstance(Delegator delegator, String webSiteId) {
        return getInstance(SeoConfig.getConfig(delegator, webSiteId), delegator, webSiteId);
    }

    /**
     * Returns an instance with possible website-specific configuration.
     */
    public static SeoCatalogUrlWorker getInstance(SeoConfig config, Delegator delegator, String webSiteId) {
        return config.getUrlWorker();
    }

    /**
     * Returns an instance that is UNABLE to perform website-specific operations.
     */
    public static SeoCatalogUrlWorker getDefaultInstance(Delegator delegator) {
        if (SeoConfig.DEBUG_FORCERELOAD) return createInstanceDeep(delegator, null);
        else return Instances.DEFAULT;
    }

    /**
     * Force create new instance - for debugging only!
     */
    public static SeoCatalogUrlWorker createInstance(Delegator delegator, String webSiteId) {
        SeoConfig config = SeoConfig.getConfig(delegator, webSiteId);
        return config.getUrlWorkerFactory().getUrlWorker(config);
    }

    /**
     * Force create new instance deep - for debugging only!
     */
    public static SeoCatalogUrlWorker createInstanceDeep(Delegator delegator, String webSiteId) {
        return new SeoCatalogUrlWorker(SeoConfig.createConfig(delegator, webSiteId));
    }

    /**
     * Boilerplate factory that returns builder instances for the CatalogUrlFilter/CatalogUrlServlet builder registry.
     * Methods return null if SEO not enabled for the webSiteId/contextPath.
     */
    public static class BuilderFactory implements CatalogAltUrlBuilder.Factory, CatalogUrlBuilder.Factory {
        private static final BuilderFactory INSTANCE = new BuilderFactory();

        public static BuilderFactory getInstance() { return INSTANCE; }
        @Override
        public CatalogUrlBuilder getCatalogUrlBuilder(Delegator delegator, FullWebappInfo targetWebappInfo) {
            SeoConfig config = SeoConfig.getConfig(delegator, targetWebappInfo);
            if (!config.isSeoUrlEnabled(targetWebappInfo.getContextPath(), targetWebappInfo.getWebSiteId())) return null;
            return SeoCatalogUrlWorker.getInstance(config, delegator, targetWebappInfo.getWebSiteId()).getCatalogUrlBuilder();
        }
        @Override
        public CatalogAltUrlBuilder getCatalogAltUrlBuilder(Delegator delegator, FullWebappInfo targetWebappInfo) {
            SeoConfig config = SeoConfig.getConfig(delegator, targetWebappInfo);
            if (!config.isSeoUrlEnabled(targetWebappInfo.getContextPath(), targetWebappInfo.getWebSiteId())) return null;
            return SeoCatalogUrlWorker.getInstance(config, delegator, targetWebappInfo.getWebSiteId()).getCatalogAltUrlBuilder();
        }
    }

    /*
     * *****************************************************
     * Getters and config
     * *****************************************************
     */

    public SeoConfig getConfig() {
        return config;
    }

    @Deprecated
    protected Locale getDefaultLocale() {
        return Locale.getDefault();
    }

    public String getConfigResourceName() {
        return configResourceName;
    }

    protected LocalizedName getProductPathName() {
        LocalizedName productPathName = this.productPathName;
        if (productPathName == null) {
            productPathName = LocalizedName.getNormalizedFromProperties(getConfigResourceName(), "SeoConfigPathNameProduct");
            this.productPathName = productPathName;
        }
        return productPathName;
    }

    public String getProductServletPathName(Locale locale) {
        return getProductPathName().getNameForLocaleOrDefault(locale);
        //return UtilProperties.getMessage(getConfigResourceName(), "SeoConfigPathNameProduct", locale);
    }

    public String getProductServletPath(Locale locale) {
        return "/" + getProductServletPathName(locale);
    }

    public Locale getProductServletPathNameLocale(String pathName) {
        return getProductPathName().getLocaleForName(pathName);
    }

    protected LocalizedName getCategoryPathName() {
        LocalizedName categoryPathName = this.categoryPathName;
        if (categoryPathName == null) {
            categoryPathName = LocalizedName.getNormalizedFromProperties(getConfigResourceName(), "SeoConfigPathNameCategory");
            this.categoryPathName = categoryPathName;
        }
        return categoryPathName;
    }

    public String getCategoryServletPathName(Locale locale) {
        return getCategoryPathName().getNameForLocaleOrDefault(locale);
        //return UtilProperties.getMessage(getConfigResourceName(), "SeoConfigPathNameCategory", locale);
    }

    @Deprecated
    public String getCategoryServletPathName() {
        return getCategoryServletPathName(getDefaultLocale());
    }

    public String getCategoryServletPath(Locale locale) {
        return "/" + getCategoryServletPathName(locale);
    }

    @Deprecated
    public String getCategoryServletPath() {
        return getCategoryServletPath(getDefaultLocale());
    }

    public Locale getCategoryServletPathNameLocale(String pathName) {
        return getCategoryPathName().getLocaleForName(pathName);
    }

    public String getUrlSuffix() {
        return config.getSeoUrlSuffix();
    }

//    public String extractProductServletPrefix(String path) {
//        throw new UnsupportedOperationException(); // TODO: if needed
//    }
//
//    public String extractCategoryServletPrefix(String path) {
//        throw new UnsupportedOperationException(); // TODO: if needed
//    }



    /*
     * *****************************************************
     * High-level helper methods (for INTERNAL use)
     * *****************************************************
     */

    /**
     * Re-generates a link from PathMatch info.
     */
    public String makeCatalogLink(Delegator delegator, PathMatch urlInfo, Locale locale) {

        // TODO: 2017

        return "";
    }

    /*
     * *****************************************************
     * Low-level URL building callbacks from makeCatalog[Alt]Link
     * *****************************************************
     */

    public CatalogUrlBuilder getCatalogUrlBuilder() {
        return catalogUrlBuilder;
    }

    public class SeoCatalogUrlBuilder extends CatalogUrlBuilder implements Serializable {
        @Override
        public String makeCatalogUrl(HttpServletRequest request, Locale locale, String productId, String currentCategoryId,
                String previousCategoryId) {
            if (UtilValidate.isNotEmpty(productId)) {
                return makeProductUrl(request, locale, previousCategoryId, currentCategoryId, productId);
            } else {
                return makeCategoryUrl(request, locale, previousCategoryId, currentCategoryId, productId, null, null, null, null);
            }
            //return CatalogUrlBuilder.getDefaultBuilder().makeCatalogUrl(request, locale, productId, currentCategoryId, previousCategoryId);
        }

        @Override
        public String makeCatalogUrl(Delegator delegator, LocalDispatcher dispatcher, Locale locale, FullWebappInfo targetWebappInfo, String currentCatalogId, List<String> crumb, String productId,
                String currentCategoryId, String previousCategoryId) {
            if (UtilValidate.isNotEmpty(productId)) {
                return makeProductUrl(delegator, dispatcher, locale, crumb, targetWebappInfo, currentCatalogId, previousCategoryId, currentCategoryId, productId);
            } else {
                return makeCategoryUrl(delegator, dispatcher, locale, crumb, targetWebappInfo, currentCatalogId, previousCategoryId, currentCategoryId, productId, null, null, null, null);
            }
            //return CatalogUrlBuilder.getDefaultBuilder().makeCatalogUrl(delegator, dispatcher, locale, contextPath, crumb, productId, currentCategoryId, previousCategoryId);
        }
    }

    public CatalogAltUrlBuilder getCatalogAltUrlBuilder() {
        return catalogAltUrlBuilder;
    }

    public class SeoCatalogAltUrlBuilder extends CatalogAltUrlBuilder implements Serializable {
        @Override
        public String makeProductAltUrl(HttpServletRequest request, Locale locale, String previousCategoryId, String productCategoryId,
                String productId) {
            return makeProductUrl(request, locale, previousCategoryId, productCategoryId, productId);
            //return CatalogAltUrlBuilder.getDefaultBuilder().makeProductAltUrl(request, locale, previousCategoryId, productCategoryId, productId);
        }

        @Override
        public String makeProductAltUrl(Delegator delegator, LocalDispatcher dispatcher, Locale locale, List<String> trail,
                FullWebappInfo targetWebappInfo, String currentCatalogId, String previousCategoryId, String productCategoryId, String productId) {
            return makeProductUrl(delegator, dispatcher, locale, trail, targetWebappInfo, currentCatalogId, previousCategoryId, productCategoryId, productId);
            //return CatalogAltUrlBuilder.getDefaultBuilder().makeProductAltUrl(delegator, dispatcher, locale, trail, contextPath, previousCategoryId, productCategoryId, productId);
        }

        @Override
        public String makeCategoryAltUrl(HttpServletRequest request, Locale locale, String previousCategoryId,
                String productCategoryId, String productId, String viewSize, String viewIndex, String viewSort,
                String searchString) {
            return makeCategoryUrl(request, locale, previousCategoryId, productCategoryId, productId, viewSize, viewIndex, viewSort, searchString);
            //return CatalogAltUrlBuilder.getDefaultBuilder().makeCategoryAltUrl(request, locale, previousCategoryId, productCategoryId, productId, viewSize, viewIndex, viewSort, searchString);
        }

        @Override
        public String makeCategoryAltUrl(Delegator delegator, LocalDispatcher dispatcher, Locale locale, List<String> trail,
                FullWebappInfo targetWebappInfo, String currentCatalogId, String previousCategoryId, String productCategoryId, String productId,
                String viewSize, String viewIndex, String viewSort, String searchString) {
            return makeCategoryUrl(delegator, dispatcher, locale, trail, targetWebappInfo, currentCatalogId, previousCategoryId, productCategoryId, productId, viewSize, viewIndex, viewSort, searchString);
            //return CatalogAltUrlBuilder.getDefaultBuilder().makeCategoryAltUrl(delegator, dispatcher, locale, trail, contextPath, previousCategoryId, productCategoryId, productId, viewSize, viewIndex, viewSort, searchString);
        }
    }

    /*
     * *****************************************************
     * URL sanitizing
     * *****************************************************
     * These control how much happens before & after storage.
     */

    public CatalogAltUrlSanitizer getCatalogAltUrlSanitizer() {
        return catalogAltUrlSanitizer;
    }

    public class SeoCatalogAltUrlSanitizer extends CatalogAltUrlSanitizer {

        @Override
        public String convertNameToDbAltUrl(String name, Locale locale, CatalogUrlType entityType, SanitizeContext ctxInfo) {
            name = processNameToAltUrl(name, locale, entityType, ctxInfo);
            name = normalizeAltUrl(name, locale, entityType, ctxInfo);
            return name;
        }

        @Override
        public String convertIdToDbAltUrl(String id, Locale locale, CatalogUrlType entityType, SanitizeContext ctxInfo) {
            // TODO: REVIEW: leaving this same as live for now... doubtful...
            return convertIdToLiveAltUrl(id, locale, entityType, ctxInfo);
        }

        @Override
        public String sanitizeAltUrlFromDb(String altUrl, Locale locale, CatalogUrlType entityType, SanitizeContext ctxInfo) {
            // WARN: due to content wrapper the locale might not be the one from the pathPart!!
            // may also be null
            if (altUrl == null) return "";

            // 2017: LEAVE THIS METHOD EMPTY - allows better DB queries if no post-processing.

            // TODO: REVIEW: REMOVED all post-db processing for now
            // - omitting could permit better DB queries
            // the reason this existed in the first place was because users can input garbage
            // through the UI, could could prevent in other ways...
            //pathPart = UrlServletHelper.invalidCharacter(pathPart); // (stock ofbiz)
            return altUrl;
        }

        @Override
        public String convertIdToLiveAltUrl(String id, Locale locale, CatalogUrlType entityType, SanitizeContext ctxInfo) {
            // TODO: REVIEW: this is what the old Seo code did, but it will just not work in the filters...
            // People should not generate DB IDs with spaces
            //return id.trim().replaceAll(" ", SeoStringUtil.URL_HYPHEN);
            return id;
        }

        protected String processNameToAltUrl(String name, Locale locale, CatalogUrlType entityType, SanitizeContext ctxInfo) {
            return getConfig().getAltUrlGenProcessors().processUrl(name);
        }

        protected String normalizeAltUrl(String name, Locale locale, CatalogUrlType entityType, SanitizeContext ctxInfo) {
            if (entityType == CatalogUrlType.PRODUCT) {
                name = truncateAltUrl(name, getConfig().getProductNameMaxLength());
            } else if (entityType == CatalogUrlType.CATEGORY) {
                name = truncateAltUrl(name, getConfig().getCategoryNameMaxLength());
            }
            return trimAltUrl(name);
        }

        protected String truncateAltUrl(String name, Integer maxLength) {
            if (name == null || maxLength == null || maxLength < 0 || name.length() <= maxLength) {
                return name;
            }
            return name.substring(0, maxLength);
        }

        protected String trimAltUrl(String name) {
            return SeoStringUtil.trimSeoName(name);
        }
    }

    /*
     * *****************************************************
     * URL building core
     * *****************************************************
     * Derived from CatalogUrlFilter methods of same names.
     * NOTE: The alt and non-alt SEO methods are unified to produce same output.
     */

    /**
     * Convert list of categoryIds to formatted alt url names, EXCLUDING the passed targetCategory (skips last elem if matches).
     * WARNING: this method may change without notice.
     * TODO?: refactor?: tries to cover too many cases
     */
    protected List<String> getCategoryTrailPathParts(Delegator delegator, LocalDispatcher dispatcher, Locale locale, List<GenericValue> trailEntities, GenericValue targetCategory,
                                                     SeoConfig.TrailFormat trailFormat, SeoConfig.TrailFormat targetCategoryFormat, CatalogUrlType urlType, CatalogAltUrlSanitizer.SanitizeContext targetSanitizeCtx, boolean useCache) {
        if (trailEntities == null || trailEntities.isEmpty()) return newPathList();
        List<String> catNames = newPathList(trailEntities.size());
        ListIterator<GenericValue> trailIt = trailEntities.listIterator();
        CatalogAltUrlSanitizer.SanitizeContext sanitizeCtx = getCatalogAltUrlSanitizer().makeSanitizeContext();
        if (targetSanitizeCtx != null) {
            sanitizeCtx.setTargetProduct(targetSanitizeCtx.getTargetProduct());
            sanitizeCtx.setTargetCategory(targetSanitizeCtx.getTargetCategory());
            sanitizeCtx.setTotalNames(targetSanitizeCtx.getTotalNames());
        }
        sanitizeCtx.setNameIndex(0);
        sanitizeCtx.setLast(false);
        String targetCategoryId = (targetCategory != null) ? targetCategory.getString("productCategoryId") : null;
        while(trailIt.hasNext()) {
            GenericValue productCategory = trailIt.next();
            if (productCategory == null) {
                continue;
            }
            String productCategoryId = productCategory.getString("productCategoryId");
            if ("TOP".equals(productCategoryId)) continue;
            if (targetCategoryId != null && !trailIt.hasNext() && targetCategoryId.equals(productCategoryId)) {
                break; // skip last if it's the target
            }
            String trailPart = getCategoryPathPart(delegator, dispatcher, locale, productCategory, trailFormat, sanitizeCtx, useCache);
            if (trailPart != null) catNames.add(trailPart);
            sanitizeCtx.increaseNameIndex();
        }
        return catNames;
    }

    /**
     * Reads ALTERNATIVE_URL for category and locale from DB and builds config-specified alt url path part, or according to the passed format.
     * Fallback on ID.
     */
    public String getCategoryPathPart(Delegator delegator, LocalDispatcher dispatcher, Locale locale, GenericValue productCategory, SeoConfig.TrailFormat format, CatalogAltUrlSanitizer.SanitizeContext sanitizeCtx, boolean useCache) {
        String name = getCategoryPathName(delegator, dispatcher, locale, productCategory, format, sanitizeCtx, useCache);
        if (UtilValidate.isNotEmpty(name)) {
            if (!sanitizeCtx.isLast()) {
                if (getConfig().isCategoryNameAppendId()) {
                    name += SeoStringUtil.URL_HYPHEN + getCatalogAltUrlSanitizer().convertIdToLiveAltUrl(productCategory.getString("productCategoryId"), locale, CatalogUrlType.CATEGORY, sanitizeCtx);
                }
            } else {
                if (getConfig().isCategoryNameAppendIdLast()) {
                    name += SeoStringUtil.URL_HYPHEN + getCatalogAltUrlSanitizer().convertIdToLiveAltUrl(productCategory.getString("productCategoryId"), locale, CatalogUrlType.CATEGORY, sanitizeCtx);
                }
            }
            return name;
        } else { // fallback
            return getCatalogAltUrlSanitizer().convertIdToLiveAltUrl(productCategory.getString("productCategoryId"), locale, CatalogUrlType.CATEGORY, sanitizeCtx);
        }
    }

    protected String getCategoryPathName(Delegator delegator, LocalDispatcher dispatcher, Locale locale, GenericValue productCategory, SeoConfig.TrailFormat format, CatalogAltUrlSanitizer.SanitizeContext sanitizeCtx, boolean useCache) {
        if (format == SeoConfig.TrailFormat.ID) {
            return getCatalogAltUrlSanitizer().convertIdToLiveAltUrl(productCategory.getString("productCategoryId"), locale, CatalogUrlType.CATEGORY, sanitizeCtx);
        }
        return getCategoryAltUrl(delegator, dispatcher, locale, productCategory, sanitizeCtx, useCache);
    }

    protected String getCategoryAltUrl(Delegator delegator, LocalDispatcher dispatcher, Locale locale, GenericValue productCategory, CatalogAltUrlSanitizer.SanitizeContext sanitizeCtx, boolean useCache) {
        String altUrl = CategoryContentWrapper.getProductCategoryContentAsText(productCategory, "ALTERNATIVE_URL", locale, dispatcher, useCache, "raw");
        if (UtilValidate.isNotEmpty(altUrl)) {
            // FIXME: effective locale might not be same as "locale" variable!
            altUrl = getCatalogAltUrlSanitizer().sanitizeAltUrlFromDb(altUrl, locale, CatalogUrlType.CATEGORY, sanitizeCtx);
        }
        return altUrl;
    }

    protected List<GenericValue> getCategoriesFromIdList(Delegator delegator, LocalDispatcher dispatcher, Locale locale, List<String> categoryIdList, boolean useCache) {
        List<GenericValue> categoryList = new ArrayList<>(categoryIdList.size());
        for(String productCategoryId : categoryIdList) {
            try {
                GenericValue productCategory = EntityQuery.use(delegator).from("ProductCategory")
                        .where("productCategoryId", productCategoryId).cache(useCache).queryOne();
                categoryList.add(productCategory);
            } catch(Exception e) {
                Debug.logError(e, "Seo: Cannot get category '" + productCategoryId + "' alt url", module);
            }
        }
        return categoryList;
    }

    protected List<String> findBestTopCatTrailForNewUrl(Delegator delegator, List<List<String>> trails, List<String> hintTrail, Collection<String> topCategoryIds) {
        if (trails.size() == 0) {
            return newPathList();
        } else if (trails.size() == 1) {
            // if only one trail, we'll assume it leads to top category
            return trails.get(0);
        } else {
            ClosestTrailResolver.ResolverType resolverType = getConfig().getNewUrlTrailResolver();
            return ensurePathList(resolverType.getResolver().findClosestTrail(trails, hintTrail, topCategoryIds));
        }
    }

    public String makeCategoryUrl(HttpServletRequest request, Locale locale, String previousCategoryId, String productCategoryId, String productId, String viewSize, String viewIndex, String viewSort, String searchString) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        List<String> trail = CategoryWorker.getTrail(request);
        FullWebappInfo targetWebappInfo = FullWebappInfo.fromRequest(request);
        String currentCatalogId = CatalogWorker.getCurrentCatalogId(request);
        return makeCategoryUrl(delegator, dispatcher, locale, trail, targetWebappInfo, currentCatalogId,
                    previousCategoryId, productCategoryId, productId, viewSize, viewIndex, viewSort, searchString);
    }

    /**
     * Make category url according to the configurations.
     */
    public String makeCategoryUrl(Delegator delegator, LocalDispatcher dispatcher, Locale locale, List<String> currentTrail, FullWebappInfo targetWebappInfo,
                                  String currentCatalogId, String previousCategoryId, String productCategoryId, String productId, String viewSize, String viewIndex, String viewSort, String searchString) {
        final boolean useCache = true; // NOTE: this is for entity cache lookups, not util caches
        List<String> trail;
        if (getConfig().getCategoryUrlTrailFormat().isOn()) {
            trail = mapCategoryUrlTrail(delegator, currentTrail, productCategoryId, targetWebappInfo.getWebSiteId(), currentCatalogId);
        } else {
            trail = Collections.emptyList();
        }
        String key = getCategoryCacheKey(delegator, targetWebappInfo, "Default", locale, previousCategoryId, productCategoryId, productId,
                viewSize, viewIndex, viewSort, searchString, currentCatalogId, trail);
        String url = categoryUrlCache.get(key);
        if (url == null) {
            url = makeCategoryUrlImpl(delegator, dispatcher, locale, trail, targetWebappInfo, currentCatalogId,
                    previousCategoryId, productCategoryId, productId, viewSize, viewIndex, viewSort, searchString, useCache);
            categoryUrlCache.put(key, url);
            if (DEBUG) {
                Debug.logInfo("Seo: makeCategoryUrl: Created category url [" + url + "] for key [" + key + "]", module);
            }
        } else {
            if (DEBUG) {
                Debug.logInfo("Seo: makeCategoryUrl: Got cached category url [" + url + "] for key [" + key + "]", module);
            }
        }
        return url;
    }

    /**
     * NOTE: caching the trail effectively renders the entries cached per-category-page, but there is no way around with without adding more complexity.
     */
    protected static String getCategoryCacheKey(Delegator delegator, FullWebappInfo targetWebappInfo, String type, Locale locale, String previousCategoryId, String productCategoryId, String productId,
                                                String viewSize, String viewIndex, String viewSort, String searchString, String currentCatalogId, List<String> trail) {
        String localeStr;
        if (locale == null) {
            localeStr = UtilProperties.getPropertyValue("scipiosetup", "store.defaultLocaleString");
            if (UtilValidate.isEmpty(localeStr)) {
                localeStr = Locale.getDefault().toString();
            }
        } else {
            localeStr = locale.toString();
        }
        return delegator.getDelegatorName()+"::"+type+"::"+targetWebappInfo.getContextPath()+"::"+targetWebappInfo.getWebSiteId()+"::"+localeStr+"::"+previousCategoryId+"::"+productCategoryId+"::"
                +productId+"::"+viewSize+"::"+viewSort+"::"+searchString+"::"+currentCatalogId+"::"+String.join("/", trail);
    }

    /**
     * Creates a full trail to the given category using hints from the incoming trail to select best.
     * Caching.
     */
    protected List<String> mapCategoryUrlTrail(Delegator delegator, List<String> hintTrail, String productCategoryId, String webSiteId, String currentCatalogId) {
        List<String> trail = null;
        String trailKey = delegator.getDelegatorName() + "::" + webSiteId + "::" + productCategoryId + "::" + currentCatalogId;
        TrailCacheEntry trailEntry = categoryTrailCache.get(trailKey);
        if (trailEntry == null) {
            Set<String> topCategoryIds = getCatalogTopCategoriesForCategoryUrl(delegator, currentCatalogId, webSiteId);
            List<List<String>> trails = null;
            if (topCategoryIds.isEmpty()) {
                Debug.logWarning("Seo: mapCategoryUrlTrail: No top categories found for catalog '" + currentCatalogId + "'; can't select best trail", module);
                topCategoryIds = null;
            } else {
                trails = getCategoryRollupTrails(delegator, productCategoryId, topCategoryIds);
            }
            trailEntry = new TrailCacheEntry(topCategoryIds, trails);
            categoryTrailCache.put(trailKey, trailEntry);
        }
        if (trailEntry.getTopCategoryIds() != null) {
            trail = findBestTopCatTrailForNewUrl(delegator, trailEntry.getTrails(), hintTrail, trailEntry.getTopCategoryIds()); // fast
        }
        return (trail != null) ? trail : Collections.emptyList();
    }

    protected String makeCategoryUrlImpl(Delegator delegator, LocalDispatcher dispatcher, Locale locale, List<String> trail, FullWebappInfo targetWebappInfo,
                                  String currentCatalogId, String previousCategoryId, String productCategoryId, String productId, String viewSize, String viewIndex, String viewSort, String searchString, boolean useCache) {
        GenericValue productCategory;
        try {
            productCategory = EntityQuery.use(delegator).from("ProductCategory").where("productCategoryId", productCategoryId).cache().queryOne();
            if (productCategory == null) {
                Debug.logWarning("Seo: Category not found: Cannot create category's URL for: " + productCategoryId, module);
                return null;
            }
        } catch (GenericEntityException e) {
            Debug.logWarning(e, "Seo: Cannot create category's URL for: " + productCategoryId, module);
            return null;
        }

        // NO LONGER NEED ADJUST - in fact it will prevent the valid trail selection after this from working
        //trail = CategoryWorker.adjustTrail(trail, productCategoryId, previousCategoryId);
        StringBuilder urlBuilder = makeCategoryUrlCore(delegator, dispatcher, locale, productCategory, currentCatalogId, previousCategoryId,
                getCategoriesFromIdList(delegator, dispatcher, locale, trail, useCache), targetWebappInfo, useCache);

        appendCategoryUrlParam(urlBuilder, "viewIndex", viewIndex);
        appendCategoryUrlParam(urlBuilder, "viewSize", viewSize);
        appendCategoryUrlParam(urlBuilder, "viewSort", viewSort);
        appendCategoryUrlParam(urlBuilder, "searchString", searchString);
        if (urlBuilder.toString().endsWith("&")) {
            return urlBuilder.toString().substring(0, urlBuilder.toString().length()-1);
        }
        return urlBuilder.toString();
    }

    private void appendCategoryUrlParam(StringBuilder urlBuilder, String paramName, String paramValue) {
        if (UtilValidate.isNotEmpty(paramValue)) {
            if (!urlBuilder.toString().endsWith("?") && !urlBuilder.toString().endsWith("&")) {
                urlBuilder.append("?");
            }
            urlBuilder.append(paramName);
            urlBuilder.append("=");
            urlBuilder.append(paramValue);
            urlBuilder.append("&");
        }
    }

    /**
     * Builds full category URL from trail of category IDs and the given category, according to configuration.
     */
    public StringBuilder makeCategoryUrlCore(Delegator delegator, LocalDispatcher dispatcher, Locale locale, GenericValue productCategory,
                                             String currentCatalogId, String previousCategoryId, List<GenericValue> trailEntities, FullWebappInfo targetWebappInfo, boolean useCache) {
        String productCategoryId = productCategory.getString("productCategoryId");
        if (trailEntities == null) {
            trailEntities = Collections.emptyList();
        } else if (!getConfig().getCategoryUrlTrailFormat().isOn() && trailEntities.size() > 1) {
            GenericValue lastElem = trailEntities.get(trailEntities.size() - 1);
            trailEntities = new ArrayList<>(1);
            trailEntities.add(lastElem);
        }
        CatalogAltUrlSanitizer.SanitizeContext targetSanitizeCtx = getCatalogAltUrlSanitizer().makeSanitizeContext()
                .setTargetCategory(productCategory).setLast(true).setNameIndex(trailEntities.size() - 1).setTotalNames(trailEntities.size()).setUseCache(useCache);
        List<String> trailNames = getCategoryTrailPathParts(delegator, dispatcher, locale, trailEntities,
                productCategory, getConfig().getCategoryUrlTrailFormat(), SeoConfig.TrailFormat.NAME, CatalogUrlType.CATEGORY, targetSanitizeCtx, useCache);
        trailNames = getCatalogAltUrlSanitizer().adjustCategoryLiveAltUrlTrail(trailNames, locale, targetSanitizeCtx);
        // NOTE: pass null productCategory because already resolved in trailNames
        return makeCategoryUrlPath(delegator, dispatcher, locale, productCategory, trailNames, targetWebappInfo.getContextPath(), targetSanitizeCtx, useCache);
    }

    /**
     * Builds the core category URL path.
     * NOTE: productCategory may be null, in which case assume already resolved as part of trailNames.
     * Assumes trailNames already valid.
     */
    public StringBuilder makeCategoryUrlPath(Delegator delegator, LocalDispatcher dispatcher, Locale locale,
                                             GenericValue productCategory, List<String> trailNames, String contextPath, CatalogAltUrlSanitizer.SanitizeContext sanitizeCtx, boolean useCache) {
        StringBuilder urlBuilder = new StringBuilder();
        if (contextPath != null) {
            urlBuilder.append(contextPath);
        }

        SeoConfig config = getConfig();

        // DEV NOTE: I removed getConfig().isHandleImplicitRequests() check from here because was inconsistent and not really needed or wanted
        boolean explicitCategoryRequest = !config.isGenerateImplicitCategoryUrl();

        if (explicitCategoryRequest && !config.isGenerateCategoryAltUrlSuffix()) {
            appendSlashAndValue(urlBuilder, getCategoryServletPathName(locale));
        }

        appendSlashAndValue(urlBuilder, trailNames);

        if (productCategory != null) {
            if (sanitizeCtx == null) {
                sanitizeCtx = getCatalogAltUrlSanitizer().makeSanitizeContext().setTargetCategory(productCategory)
                        .setLast(true).setNameIndex(trailNames.size()).setTotalNames(trailNames.size() + 1).setUseCache(useCache);
            }
            String catTrailName = getCategoryPathPart(delegator, dispatcher, locale, productCategory, null, sanitizeCtx, useCache);
            if (catTrailName != null) {
                appendSlashAndValue(urlBuilder, catTrailName);
            }
        }

        // legacy category alt url suffix ("-c")
        if (explicitCategoryRequest && config.isGenerateCategoryAltUrlSuffix()) {
            urlBuilder.append(config.getCategoryAltUrlSuffix());
        }

        // general URL suffix/extension (".html")
        checkAddUrlSuffix(urlBuilder);

        return getCatalogAltUrlSanitizer().adjustCategoryLiveAltUrlPath(urlBuilder, locale, sanitizeCtx);
    }

    public StringBuilder makeCategoryUrlPath(Delegator delegator, LocalDispatcher dispatcher, Locale locale,
                                             GenericValue productCategory, List<String> trailNames, String contextPath, boolean useCache) {
        return makeCategoryUrlPath(delegator, dispatcher, locale, productCategory, trailNames, contextPath, null, useCache);
    }

    public String makeProductUrl(HttpServletRequest request, Locale locale, String previousCategoryId, String productCategoryId, String productId) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        List<String> trail = CategoryWorker.getTrail(request);
        FullWebappInfo targetWebappInfo = FullWebappInfo.fromRequest(request);
        String currentCatalogId = CatalogWorker.getCurrentCatalogId(request);
        return makeProductUrl(delegator, dispatcher, locale, trail, targetWebappInfo, currentCatalogId, previousCategoryId, productCategoryId, productId);
    }

    /**
     * Make product url according to the configurations.
     * <p>
     * SCIPIO: Modified for bugfixes and lookup via cache products map.
     */
    public String makeProductUrl(Delegator delegator, LocalDispatcher dispatcher, Locale locale, List<String> currentTrail, FullWebappInfo targetWebappInfo,
                                 String currentCatalogId, String previousCategoryId, String productCategoryId, String productId) {
        final boolean useCache = true; // NOTE: this is for entity cache lookups, not util caches
        List<String> trail;
        if (!getConfig().isCategoryNameEnabled() && !getConfig().getProductUrlTrailFormat().isOn()) {
            trail = Collections.emptyList(); // no need for trail
        } else {
            // NO LONGER NEED ADJUST (stock ofbiz logic) - in fact it will prevent the valid trail selection after this from working
            //if (UtilValidate.isNotEmpty(productCategoryId)) {
            //    currentTrail = CategoryWorker.adjustTrail(currentTrail, productCategoryId, previousCategoryId);
            //}
            trail = mapProductUrlTrail(delegator, currentTrail, productId, targetWebappInfo.getWebSiteId(), currentCatalogId);
        }
        String key = getProductUrlCacheKey(delegator, targetWebappInfo, "Default", locale, previousCategoryId, productCategoryId,
                productId, currentCatalogId, trail);
        String url = productUrlCache.get(key);
        if (url == null) {
            url = makeProductUrlImpl(delegator, dispatcher, locale, trail, targetWebappInfo, currentCatalogId, previousCategoryId, productCategoryId, productId, useCache);
            productUrlCache.put(key, url);
            if (DEBUG) {
                Debug.logInfo("makeProductUrl: Created product url [" + url + "] for key [" + key + "]", module);
            }
        } else {
            if (DEBUG) {
                Debug.logInfo("makeProductUrl: Got cached product url [" + url + "] for key [" + key + "]", module);
            }
        }
        return url;
    }

    protected static String getProductUrlCacheKey(Delegator delegator, FullWebappInfo targetWebappInfo, String type, Locale locale, String previousCategoryId, String productCategoryId, String productId, String currentCatalogId, List<String> trail) {
        String localeStr;
        if (locale == null) {
            localeStr = UtilProperties.getPropertyValue("scipiosetup", "store.defaultLocaleString");
            if (UtilValidate.isEmpty(localeStr)) {
                localeStr = Locale.getDefault().toString();
            }
        } else {
            localeStr = locale.toString();
        }
        return delegator.getDelegatorName()+"::"+type+"::"+targetWebappInfo.getContextPath()+"::"+targetWebappInfo.getWebSiteId()+"::"+localeStr+"::"+previousCategoryId+"::"+productCategoryId+"::"
                +productId+"::"+currentCatalogId+"::"+String.join("/", trail);
    }

    /**
     * Creates a full trail to the given product using hints from the incoming trail to select best.
     */
    protected List<String> mapProductUrlTrail(Delegator delegator, List<String> hintTrail, String productId, String webSiteId, String currentCatalogId) {
        List<String> trail = null;
        String trailKey = delegator.getDelegatorName() + "::" + webSiteId + "::" + productId + "::" + currentCatalogId;
        TrailCacheEntry trailEntry = productTrailCache.get(trailKey);
        if (trailEntry == null) {
            GenericValue product = null;
            try {
                product = EntityQuery.use(delegator).from("Product").where("productId", productId).cache().queryOne();
            } catch (GenericEntityException e) {
                Debug.logWarning(e, "Seo: Cannot create product's URL for: " + productId, module);
            }
            Set<String> topCategoryIds = null;
            List<List<String>> trails = null;
            if (product != null) {
                topCategoryIds = getCatalogTopCategoriesForProductUrl(delegator, currentCatalogId, webSiteId);
                if (topCategoryIds.isEmpty()) {
                    Debug.logWarning("Seo: mapProductUrlTrail: No top category found for catalog '" + currentCatalogId + "'; can't select best trail", module);
                    topCategoryIds = null;
                } else {
                    try {
                        String primaryCatId = product.getString("primaryProductCategoryId");
                        if (primaryCatId != null) { // prioritize primary product category
                            trails = getCategoryRollupTrails(delegator, primaryCatId, topCategoryIds);
                        } else { // no primary, use rollups
                            List<GenericValue> prodCatMembers = EntityQuery.use(delegator).from("ProductCategoryMember")
                                    .where("productId", productId).orderBy("-fromDate").filterByDate().cache().queryList();
                            if (prodCatMembers.size() > 0) {
                                //trails = null;
                                for (GenericValue prodCatMember : prodCatMembers) {
                                    String productCategoryId = prodCatMember.getString("productCategoryId");
                                    List<List<String>> memberTrails = getCategoryRollupTrails(delegator, productCategoryId, topCategoryIds);
                                    if (trails == null) trails = memberTrails;
                                    else trails.addAll(memberTrails);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Debug.logError(e, "Seo: Error generating trail for product '" + productId + "': " + e.getMessage(), module);
                    }
                }
            }
            trailEntry = new TrailCacheEntry(topCategoryIds, trails);
            productTrailCache.put(trailKey, trailEntry);
        }
        if (trailEntry.getTopCategoryIds() != null) {
            trail = findBestTopCatTrailForNewUrl(delegator, trailEntry.getTrails(), hintTrail, trailEntry.getTopCategoryIds()); // fast
        }
        return (trail != null) ? trail : Collections.emptyList();
    }

    /**
     * Make product url according to the configurations.
     * <p>
     * SCIPIO: Modified for bugfixes and lookup via cache products map.
     */
    protected String makeProductUrlImpl(Delegator delegator, LocalDispatcher dispatcher, Locale locale, List<String> trail, FullWebappInfo targetWebappInfo,
                                 String currentCatalogId, String previousCategoryId, String productCategoryId, String productId, boolean useCache) {
        GenericValue product;
        try {
            product = EntityQuery.use(delegator).from("Product").where("productId", productId).cache().queryOne();
        } catch (GenericEntityException e) {
            Debug.logWarning(e, "Seo: Cannot create product's URL for: " + productId, module);
            return null;
        }
        return makeProductUrlCore(delegator, dispatcher, locale, product, currentCatalogId, previousCategoryId,
                getCategoriesFromIdList(delegator, dispatcher, locale, trail, useCache), targetWebappInfo, useCache).toString();
    }

    /**
     * Builds full product URL from trail of category IDs and the given product, according to configuration.
     */
    public StringBuilder makeProductUrlCore(Delegator delegator, LocalDispatcher dispatcher, Locale locale, GenericValue product,
                                            String currentCatalogId, String previousCategoryId, List<GenericValue> trailEntities, FullWebappInfo targetWebappInfo, boolean useCache) {
        CatalogAltUrlSanitizer.SanitizeContext targetSanitizeCtx = getCatalogAltUrlSanitizer().makeSanitizeContext().setLast(true).setNameIndex(trailEntities.size()).setTotalNames(trailEntities.size() + 1).setUseCache(useCache);
        List<String> trailNames = Collections.emptyList();
        if (UtilValidate.isNotEmpty(trailEntities) && getConfig().isCategoryNameEnabled() && getConfig().getProductUrlTrailFormat().isOn()) {
            trailNames = getCategoryTrailPathParts(delegator, dispatcher, locale, trailEntities,
                    null, getConfig().getProductUrlTrailFormat(), null, CatalogUrlType.PRODUCT, targetSanitizeCtx, useCache);
        }
        trailNames = getCatalogAltUrlSanitizer().adjustProductLiveAltUrlTrail(trailNames, locale, targetSanitizeCtx);
        return makeProductUrlPath(delegator, dispatcher, locale, product, trailNames, targetWebappInfo.getContextPath(), targetSanitizeCtx, useCache);
    }

    /**
     * Builds the core product URL path.
     * NOTE: product may be null, in which case assume already resolved as part of trailNames.
     * Assumes trailNames already valid.
     */
    public StringBuilder makeProductUrlPath(Delegator delegator, LocalDispatcher dispatcher, Locale locale, GenericValue product, List<String> trailNames, String contextPath, CatalogAltUrlSanitizer.SanitizeContext sanitizeCtx, boolean useCache) {
        StringBuilder urlBuilder = new StringBuilder();
        if (contextPath != null) {
            urlBuilder.append(contextPath);
        }

        SeoConfig config = getConfig();

        int catCount = trailNames.size();
        if (product == null && catCount > 0) catCount--; // product already in trail

        boolean explicitProductRequest;
        // DEV NOTE: I removed config.isHandleImplicitRequests() check from here because was inconsistent and not really needed or wanted
        if (catCount > 0) {
            explicitProductRequest = !config.isGenerateImplicitProductUrl();
        } else {
            // NOTE: in old code, seo only support(ed) implicit request if there was at least one category
            explicitProductRequest = !config.isGenerateImplicitProductUrlNoCat();
        }

        if (explicitProductRequest && !config.isGenerateProductAltUrlSuffix()) {
            appendSlashAndValue(urlBuilder, getProductServletPathName(locale));
        }

        // append category names
        if (config.isCategoryNameEnabled() && config.getProductUrlTrailFormat().isOn()) {
            appendSlashAndValue(urlBuilder, trailNames);
        }

        if (product != null) {
            // 2017-11-08: NOT SUPPORTED: could only theoretically work if chose different character than hyphen
            //if (!trailNames.isEmpty() && !SeoConfigUtil.isCategoryNameSeparatePathElem()) {
            //    urlBuilder.append(SeoStringUtil.URL_HYPHEN);
            //} else {
            ensureTrailingSlash(urlBuilder);
            //}

            if (sanitizeCtx == null) {
                sanitizeCtx = getCatalogAltUrlSanitizer().makeSanitizeContext().setTargetProduct(product).setUseCache(useCache);
            }
            urlBuilder.append(getProductPathPart(delegator, dispatcher, locale, product, sanitizeCtx, useCache));
        }

        // legacy product alt url suffix ("-p")
        if (explicitProductRequest && config.isGenerateProductAltUrlSuffix()) {
            urlBuilder.append(config.getProductAltUrlSuffix());
        }

        // general URL suffix/extension (".html")
        checkAddUrlSuffix(urlBuilder);

        return getCatalogAltUrlSanitizer().adjustProductLiveAltUrlPath(urlBuilder, locale, sanitizeCtx);
    }

    public StringBuilder makeProductUrlPath(Delegator delegator, LocalDispatcher dispatcher, Locale locale, GenericValue product, List<String> trailNames, String contextPath, boolean useCache) {
        return makeProductUrlPath(delegator, dispatcher, locale, product, trailNames, contextPath, null, useCache);
    }

    protected String getProductPathPart(Delegator delegator, LocalDispatcher dispatcher, Locale locale, GenericValue product, CatalogAltUrlSanitizer.SanitizeContext sanitizeCtx, boolean useCache) {
        String name = getProductPathName(delegator, dispatcher, locale, product, sanitizeCtx, useCache);
        if (UtilValidate.isNotEmpty(name)) {
            if (config.isProductNameAppendId()) {
                return getConfig().getProductUrlTargetPattern().expandString(UtilMisc.toMap("name", name, "id",
                        getCatalogAltUrlSanitizer().convertIdToLiveAltUrl(product.getString("productId"), locale, CatalogUrlType.PRODUCT, sanitizeCtx)));
            } else {
                return name;
            }
        } else { // fallback
            return getCatalogAltUrlSanitizer().convertIdToLiveAltUrl(product.getString("productId"), locale, CatalogUrlType.PRODUCT, sanitizeCtx);
        }
    }

    protected String getProductPathName(Delegator delegator, LocalDispatcher dispatcher, Locale locale, GenericValue product, CatalogAltUrlSanitizer.SanitizeContext sanitizeCtx, boolean useCache) {
        return getProductAltUrl(delegator, dispatcher, locale, product, sanitizeCtx, useCache);
    }

    protected String getProductAltUrl(Delegator delegator, LocalDispatcher dispatcher, Locale locale, GenericValue product, CatalogAltUrlSanitizer.SanitizeContext sanitizeCtx, boolean useCache) {
        String altUrl = ProductContentWrapper.getProductContentAsText(product, "ALTERNATIVE_URL", locale, dispatcher, useCache, "raw");
        if (UtilValidate.isNotEmpty(altUrl)) {
            // FIXME: effective locale might not be same as "locale" variable!
            return getCatalogAltUrlSanitizer().sanitizeAltUrlFromDb(altUrl, locale, CatalogUrlType.PRODUCT, sanitizeCtx);
        }
        return null;
    }

    protected void checkAddUrlSuffix(StringBuilder sb) {
        String urlSuffix = getUrlSuffix();
        if (UtilValidate.isNotEmpty(urlSuffix) && (sb.length() > 0) && (sb.charAt(sb.length() - 1) != '/')) {
            sb.append(urlSuffix);
        }
    }

    /*
     * *****************************************************
     * URL matching & parsing
     * *****************************************************
     */

    public PathMatch makePathMatchIfValidRequest(Delegator delegator, String path, String contextPath, String webSiteId, String currentCatalogId,
                                                 Timestamp moment, PathPartAndTrailMatch pathPartAndTrailMatch,
                                                 boolean explicitProductRequest, boolean explicitCategoryRequest,
                                                 List<String> pathElements, Locale matchedLocale) {
        if (explicitProductRequest || explicitCategoryRequest || pathPartAndTrailMatch != null) {
            return makePathMatch(delegator, path, contextPath, webSiteId, currentCatalogId, moment, pathPartAndTrailMatch,
                    explicitProductRequest, explicitCategoryRequest, pathElements, matchedLocale);
        }
        return null;
    }

    public PathMatch makePathMatch(Delegator delegator, String path, String contextPath, String webSiteId, String currentCatalogId,
                                   Timestamp moment, PathPartAndTrailMatch pathPartAndTrailMatch,
                                   boolean explicitProductRequest, boolean explicitCategoryRequest,
                                   List<String> pathElements, Locale locale) {
        return new PathMatch(path, contextPath, webSiteId, currentCatalogId, moment, pathPartAndTrailMatch,
                explicitProductRequest, explicitCategoryRequest, pathElements, locale);
    }

    /**
     * Returned whenever we find a URL that appears to be an SEO URL, even if the request is not for a valid product or category.
     */
    public static class PathMatch implements Serializable {
        protected String path;
        protected List<String> pathElements; // path elements (after mount-point, if any)
        protected Locale locale;
        protected PathPartMatch targetMatch; // The actual match
        protected List<String> trailCategoryIds;
        protected boolean explicitProductRequest;
        protected boolean explicitCategoryRequest;

        public PathMatch() {
        }

        public PathMatch(String path, String contextPath, String webSiteId, String currentCatalogId,
                         Timestamp moment, PathPartAndTrailMatch pathPartAndTrailMatch,
                         boolean explicitProductRequest, boolean explicitCategoryRequest,
                         List<String> pathElements, Locale locale) {
            this.targetMatch = (pathPartAndTrailMatch != null) ? pathPartAndTrailMatch.getTargetMatch() : null;
            this.trailCategoryIds = (pathPartAndTrailMatch != null && pathPartAndTrailMatch.getTrailCategoryIds() != null) ? pathPartAndTrailMatch.getTrailCategoryIds() : Collections.emptyList();
            this.path = path;
            this.explicitProductRequest = explicitProductRequest;
            this.explicitCategoryRequest = explicitCategoryRequest;
            this.pathElements = ensurePathList(pathElements);
            this.locale = locale;
        }

        public PathMatch(PathMatch other) {
            this.targetMatch = other.targetMatch;
            this.trailCategoryIds = other.trailCategoryIds;
            this.path = other.path;
            this.explicitProductRequest = other.explicitProductRequest;
            this.explicitCategoryRequest = other.explicitCategoryRequest;
            this.pathElements = other.pathElements;
            this.locale = other.locale;
        }

        /** Returns the original path that was matched. **/
        public String getPath() { return path; }
        /** Returns the original path elements, excluding any explicit /product or /category mount-point. **/
        public List<String> getPathElements() { return pathElements; }
        /**
         * The locale that the explicit product/category path prefix matched OR the
         * the locale that the product/category name matched for implicit mapping.
         * WARN: may be imprecise! multiple labels may map to the same language!
         */
        public Locale getLocale() { return locale; }

        /** Returns the target match, or null if no productId or categoryId could be matched. **/
        public PathPartMatch getTargetMatch() { return targetMatch; }
        public String getTargetProductId() { return hasTargetProduct() ? getTargetMatch().getId() : null; }
        public String getTargetCategoryId() { return hasTargetCategory() ? getTargetMatch().getId() : null; }

        public List<String> getTrailCategoryIds() { return trailCategoryIds; }
        public String getParentCategoryId() { return (getTrailCategoryIds().size() > 0) ? getTrailCategoryIds().get(getTrailCategoryIds().size() - 1) : null; }

        public boolean hasTargetProduct() { return (getTargetMatch() != null && getTargetMatch().getEntityType() == CatalogUrlType.PRODUCT); }
        public boolean hasTargetCategory() { return (getTargetMatch() != null && getTargetMatch().getEntityType() == CatalogUrlType.CATEGORY); }

        public boolean isExplicitProductRequest() { return explicitProductRequest; }
        public boolean isExplicitCategoryRequest() { return explicitCategoryRequest; }

        public boolean isProductRequest() { return isExplicitProductRequest() || hasTargetProduct(); }
        public boolean isCategoryRequest() { return isExplicitCategoryRequest() || hasTargetCategory(); }

        public PathMatch setPath(String path) {
            this.path = path;
            return this;
        }

        public PathMatch setPathElements(List<String> pathElements) {
            this.pathElements = pathElements;
            return this;
        }

        public PathMatch setLocale(Locale locale) {
            this.locale = locale;
            return this;
        }

        public PathMatch setTargetMatch(PathPartMatch targetMatch) {
            this.targetMatch = targetMatch;
            return this;
        }

        public PathMatch setTrailCategoryIds(List<String> trailCategoryIds) {
            this.trailCategoryIds = trailCategoryIds;
            return this;
        }

        public PathMatch setExplicitProductRequest(boolean explicitProductRequest) {
            this.explicitProductRequest = explicitProductRequest;
            return this;
        }

        public PathMatch setExplicitCategoryRequest(boolean explicitCategoryRequest) {
            this.explicitCategoryRequest = explicitCategoryRequest;
            return this;
        }
    }

    /**
     * Checks if the path (starting after context path) appears to be an SEO
     * URL and returns its info if so.
     *
     * @param path path starting from context path, in other words servlet path + path info
     */
    public PathMatch matchInboundSeoCatalogUrl(Delegator delegator, String path, String contextPath, String webSiteId, String currentCatalogId) {
        // clean up the path
        String pathInfo = preprocessInboundSeoCatalogUrlPath(path);
        if (pathInfo == null) {
            return null;
        }

        // check/strip the URL suffix
        // 2020-01-24: This used to be done in preprocessInboundSeoCatalogUrlPath, but the trailing slash
        // has to be stripped BEFORE the suffix stripped (it makes no sense for suffix to be separated by a slash)
        // and we need to handle when the suffix is configured but missing
        String urlSuffix = getUrlSuffix();
        boolean urlSuffixFail = false;
        if (UtilValidate.isNotEmpty(urlSuffix)) {
            if (pathInfo.endsWith(urlSuffix)) {
                pathInfo = pathInfo.substring(0, pathInfo.length() - urlSuffix.length());
            } else if (config.isSeoUrlSuffixMatchRequired()) {
                urlSuffixFail = true;
            }
        }

        // split path into alt-url parts
        List<String> pathElements = StringUtil.split(pathInfo, "/");
        if (UtilValidate.isEmpty(pathElements)) {
            return null;
        }

        boolean explicitCategoryRequest = false;
        boolean explicitProductRequest = false;
        String productId = null;
        String categoryId = null;
        // the locale the URL appears to be: the path prefix if explicit, or the cat/prod name language if implicit
        Locale matchedLocale = null;
        String lastPathElem = pathElements.get(pathElements.size() - 1);

        SeoConfig config = getConfig();

        // determine the general form of the URL: explicit or implicit (+ locale at same time)
        matchedLocale = getProductServletPathNameLocale(pathElements.get(0));
        if (matchedLocale != null) {
            explicitProductRequest = true;
            pathElements.remove(0);
        } else {
            matchedLocale = getCategoryServletPathNameLocale(pathElements.get(0));
            if (matchedLocale != null) {
                explicitCategoryRequest = true;
                pathElements.remove(0);
            } else {
                // LEGACY suffix support for backward compat with published links
                if (config.isHandleProductAltUrlSuffix() && lastPathElem.endsWith(config.getProductAltUrlSuffix())) {
                    explicitProductRequest = true;
                    lastPathElem = lastPathElem.substring(0, lastPathElem.length() - config.getProductAltUrlSuffix().length());
                    pathElements.set(pathElements.size() - 1, lastPathElem);
                } else if (config.isHandleCategoryAltUrlSuffix() && lastPathElem.endsWith(config.getCategoryAltUrlSuffix())) {
                    explicitCategoryRequest = true;
                    lastPathElem = lastPathElem.substring(0, lastPathElem.length() - config.getProductAltUrlSuffix().length());
                    pathElements.set(pathElements.size() - 1, lastPathElem);
                } else {
                    if (!config.isHandleImplicitRequests()) {
                        return null;
                    }
                }
            }
        }

        if (pathElements.size() > 0) {
            lastPathElem = pathElements.get(pathElements.size() - 1);
        } else {
            lastPathElem = null;
        }

        PathPartAndTrailMatch pathPartAndTrailMatch = null;
        List<String> allPathElements = new ArrayList<>(pathElements);
        Timestamp moment = UtilDateTime.nowTimestamp();
        if (UtilValidate.isNotEmpty(lastPathElem) && !urlSuffixFail) {
            pathElements.remove(pathElements.size() - 1);
            String firstPathElem = (pathElements.size() > 0) ? pathElements.get(0) : lastPathElem;
            try {
                if (explicitProductRequest) {
                    // EXPLICIT PRODUCT
                    PathPartMatches productMatches = null;
                    if (getConfig().isProductSimpleIdLookup() && UtilValidate.isNotEmpty(firstPathElem)) {
                        productMatches = matchPathPartProductCached(delegator, firstPathElem, PathPartMatchOptions.IDONLY, moment);
                    }
                    if (productMatches == null || productMatches.isEmpty()) {
                        productMatches = matchPathPartProductCached(delegator, lastPathElem, PathPartMatchOptions.ALL, moment);
                    }
                    if (productMatches.size() > 0) {
                        pathPartAndTrailMatch = findBestProductMatch(delegator, productMatches, pathElements, currentCatalogId, webSiteId, moment);
                    }
                } else if (explicitCategoryRequest) {
                    // EXPLICIT CATEGORY
                    PathPartMatches categoryMatches = null;
                    if (getConfig().isCategorySimpleIdLookup() && UtilValidate.isNotEmpty(firstPathElem)) {
                        categoryMatches = matchPathPartCategoryCached(delegator, firstPathElem, PathPartMatchOptions.IDONLY, moment);
                    }
                    if (categoryMatches == null || categoryMatches.isEmpty()) {
                        categoryMatches = matchPathPartCategoryCached(delegator, lastPathElem, PathPartMatchOptions.ALL, moment);
                    }
                    if (categoryMatches.size() > 0) {
                        pathPartAndTrailMatch = findBestCategoryMatch(delegator, categoryMatches, pathElements, currentCatalogId, webSiteId, moment);
                    }
                } else {
                    // IMPLICIT REQUEST
                    // WARN: best-effort, ambiguous - it is up to SeoConfig.xml to decide how risky this will be
                    PathPartMatchOptions matchOptions = config.isImplicitRequestNameMatchesOnly() ? PathPartMatchOptions.ALL_NAMEONLY : PathPartMatchOptions.ALL;
                    PathPartMatches productMatches = matchPathPartProductCached(delegator, lastPathElem, matchOptions, moment);
                    if (productMatches.size() > 0) {
                        pathPartAndTrailMatch = findBestProductMatch(delegator, productMatches, pathElements, currentCatalogId, webSiteId, moment);
                    } else {
                        PathPartMatches categoryMatches = matchPathPartCategoryCached(delegator, lastPathElem, matchOptions, moment);
                        if (categoryMatches.size() > 0) {
                            pathPartAndTrailMatch = findBestCategoryMatch(delegator, categoryMatches, pathElements, currentCatalogId, webSiteId, moment);
                        }
                    }
                }
            } catch(Exception e) {
                Debug.logError(e, "Seo: matchInboundSeoCatalogUrl: Error parsing catalog URL " + path + ": " + e.getMessage(), module);
                return null;
            }
        }

        return makePathMatchIfValidRequest(delegator, path, contextPath, webSiteId, currentCatalogId, moment, pathPartAndTrailMatch, explicitProductRequest, explicitCategoryRequest, allPathElements, matchedLocale);
    }

    /**
     * Local class used to return PathPartMatch and trailCategoryIds together from the methods.
     */
    public static class PathPartAndTrailMatch implements Serializable {
        protected final PathPartMatch targetMatch;
        protected final List<String> trailCategoryIds; // May be null (invalid path) - do not set empty list here if received null!
        protected PathPartAndTrailMatch(PathPartMatch targetMatch, List<String> pathCategoryIds) {
            if (targetMatch.getEntityType() == CatalogUrlType.CATEGORY) {
                removeLastIfEquals(pathCategoryIds, targetMatch.getId());
            }
            this.targetMatch = targetMatch;
            this.trailCategoryIds = pathCategoryIds;
        }
        public PathPartMatch getTargetMatch() { return targetMatch; }
        public List<String> getTrailCategoryIds() { return trailCategoryIds; }
    }

    protected PathPartAndTrailMatch findBestProductMatch(Delegator delegator, PathPartMatches productMatches, List<String> pathElements, String currentCatalogId, String webSiteId, Timestamp moment) throws GenericEntityException {
        List<String> pathCategoryIds = null;
        Set<String> topCategoryIds = getCatalogTopCategoriesForProductUrl(delegator, currentCatalogId, webSiteId);
        PathPartMatch singleMatch = productMatches.getSingle();
        if (singleMatch != null) {
            // SINGLE PRODUCT RESULT
            List<List<String>> possibleTrails = getProductRollupTrails(delegator, singleMatch.getId(), topCategoryIds);
            if (possibleTrails.size() > 0) {
                pathCategoryIds = findSingleMatchBestTrail(delegator, possibleTrails, pathElements, null, topCategoryIds, moment);
                if (pathCategoryIds != null || config.isAllowInvalidCategoryPathElements()) {
                    return new PathPartAndTrailMatch(singleMatch, pathCategoryIds);
                }
            } else {
                if (config.isAllowTargetOutsideCatalog()) {
                    return new PathPartAndTrailMatch(singleMatch, pathCategoryIds);
                }
            }
        } else {
            // MULTIPLE PRODUCT RESULT (NAME/ID CONFLICT)
            PathPartAndTrailMatch bestMatch = findMultiMatchBestMatch(delegator, productMatches, false, pathElements, topCategoryIds, moment);
            if (bestMatch != null) {
                if (bestMatch.getTrailCategoryIds() != null || config.isAllowInvalidCategoryPathElements()) {
                    return bestMatch;
                }
            } else {
                if (config.isAllowTargetOutsideCatalog()) {
                    return new PathPartAndTrailMatch(productMatches.getFirst(), null);
                }
            }
        }
        return null;
    }

    protected PathPartAndTrailMatch findBestCategoryMatch(Delegator delegator, PathPartMatches categoryMatches, List<String> pathElements, String currentCatalogId, String webSiteId, Timestamp moment) throws GenericEntityException {
        List<String> pathCategoryIds = null;
        Set<String> topCategoryIds = getCatalogTopCategoriesForCategoryUrl(delegator, currentCatalogId, webSiteId);
        PathPartMatch singleMatch = categoryMatches.getSingle();
        if (singleMatch != null) {
            // SINGLE CATEGORY RESULT
            List<List<String>> possibleTrails = getCategoryRollupTrails(delegator, singleMatch.getId(), topCategoryIds);
            if (possibleTrails.size() > 0) {
                pathCategoryIds = findSingleMatchBestTrail(delegator, possibleTrails, pathElements, categoryMatches, topCategoryIds, moment);
                if (pathCategoryIds != null || config.isAllowInvalidCategoryPathElements()) {
                    return new PathPartAndTrailMatch(singleMatch, pathCategoryIds);
                }
            } else {
                if (config.isAllowTargetOutsideCatalog()) {
                    return new PathPartAndTrailMatch(singleMatch, pathCategoryIds);
                }
            }
        } else {
            // MULTIPLE CATEGORY RESULT (NAME/ID CONFLICT)
            // find the most exact category that belongs to our store, and give priority
            // to one that matches path elements. if there are no path elements, give priority to full name+id match.
            PathPartAndTrailMatch bestMatch = findMultiMatchBestMatch(delegator, categoryMatches, true, pathElements, topCategoryIds, moment);
            if (bestMatch != null) {
                if (bestMatch.getTrailCategoryIds() != null || config.isAllowInvalidCategoryPathElements()) {
                    return bestMatch;
                }
            } else {
                if (config.isAllowTargetOutsideCatalog()) {
                    return new PathPartAndTrailMatch(categoryMatches.getFirst(), null);
                }
            }
        }
        return null;
    }

    protected List<String> findSingleMatchBestTrail(Delegator delegator, List<List<String>> possibleTrails, List<String> pathElements, PathPartMatches extraPathElement, Set<String> topCategoryIds, Timestamp moment) throws GenericEntityException {
        List<String> pathCategoryIds = null;

        if (possibleTrails.size() == 1 && getConfig().isAllowInvalidCategoryPathElements()) {
            // optimization: only one trail possible, path elements not important, so can ignore path elements
            pathCategoryIds = possibleTrails.get(0);
        } else {
            if (pathElements.isEmpty()) {
                // no trail hint, so just select the first...
                //trailCategoryIds = possibleTrails.get(0);
                pathCategoryIds = getFirstTopTrail(possibleTrails, topCategoryIds);
            } else {
                // find the trail closest to the passed path elems
                List<PathPartMatches> resolvedPathElems = matchPathPartCategoriesCached(delegator, pathElements, PathPartMatchOptions.ALL, moment);
                if (extraPathElement != null) { // needed for categories
                    resolvedPathElems.add(extraPathElement);
                }
                pathCategoryIds = findBestTrailForUrlPathElems(delegator, possibleTrails, resolvedPathElems);

                if (pathCategoryIds == null && getConfig().isAllowInvalidCategoryPathElements()) {
                    pathCategoryIds = getFirstTopTrail(possibleTrails, topCategoryIds);
                }
            }
        }

        return pathCategoryIds;
    }

    protected PathPartAndTrailMatch findMultiMatchBestMatch(Delegator delegator, PathPartMatches matches, boolean isCategory, List<String> pathElements, Set<String> topCategoryIds, Timestamp moment) throws GenericEntityException {
        List<PathPartMatches> resolvedPathElems = matchPathPartCategoriesCached(delegator, pathElements, PathPartMatchOptions.ALL, moment);
        List<String> pathCategoryIds = null;

        PathPartMatch bestMatch = null;
        List<List<String>> bestMatchTrails = null;
        List<String> bestPathCategoryIds = null; // NOTE: this contains the target category itself at end
        for(PathPartMatch nextMatch : matches.values()) {
            List<List<String>> nextMatchTrails;
            if (isCategory) {
                nextMatchTrails = getCategoryRollupTrails(delegator, nextMatch.getId(), topCategoryIds);
            } else {
                nextMatchTrails = getProductRollupTrails(delegator, nextMatch.getId(), topCategoryIds);
            }
            if (nextMatchTrails.size() > 0) {
                if (pathElements.size() > 0) {
                    if (isCategory) {
                        // SPECIAL: for category, we have to re-add ourselves at the end for checking purposes
                        resolvedPathElems.add(nextMatch.getAsResult());
                    }
                    try {
                        List<String> nextPathCategoryIds = findBestTrailForUrlPathElems(delegator, nextMatchTrails, resolvedPathElems);
                        if (nextPathCategoryIds != null) {
                            // here, nextPathCategoryIds returned is always equal size or longer than pathElements.
                            // the best trailCategoryIds is actually the shortest one, because it's closest to pathElements.
                            if (bestMatch == null || (nextPathCategoryIds != null &&
                                ((bestPathCategoryIds == null) ||
                                (nextPathCategoryIds.size() < bestPathCategoryIds.size()) ||
                                (nextPathCategoryIds.size() == bestPathCategoryIds.size() &&
                                        isFirstTrailIsBetterMatchThanSecondTopCatPrecision(nextMatch, nextPathCategoryIds, bestMatch, bestPathCategoryIds, topCategoryIds))))) {
                                bestMatch = nextMatch;
                                bestMatchTrails = nextMatchTrails;
                                bestPathCategoryIds = nextPathCategoryIds;
                                // not sure about this anymore... better omit
                                //// special case: we won't find better than this (with out current algos)
                                //if (nextMatch.isFullMatch() && (nextPathCategoryIds != null && nextPathCategoryIds.size() == (pathElements.size()+1))) {
                                //    break;
                                //}
                            }
                        }
                    } finally {
                        if (isCategory) {
                            resolvedPathElems.remove(resolvedPathElems.size() - 1);
                        }
                    }
                } else {
                    List<String> nextPathCategoryIds = getFirstTopTrail(nextMatchTrails, topCategoryIds);
                    if (bestMatch == null || isFirstTrailIsBetterMatchThanSecondTopCatPrecision(nextMatch, nextPathCategoryIds, bestMatch, bestPathCategoryIds, topCategoryIds)) {
                        bestMatch = nextMatch;
                        bestMatchTrails = nextMatchTrails;
                        bestPathCategoryIds = nextPathCategoryIds;
                        // not sure about this anymore... better omit
                        //// special case: we won't find better than this
                        //if (nextMatch.isFullMatch()) break;
                    }
                }
            }
        }
        if (bestMatch != null) {
            if (pathElements.size() > 0) {
                pathCategoryIds = bestPathCategoryIds;
                // NOTE: I don't think this ever gets called, leaving here just in case
                if (pathCategoryIds == null && getConfig().isAllowInvalidCategoryPathElements()) {
                    pathCategoryIds = getFirstTopTrail(bestMatchTrails, topCategoryIds);
                }
            } else {
                // now do this during iteration, otherwise we can't do the priority properly
                //trailCategoryIds = getFirstTopTrail(bestMatchTrails, topCategoryIds);
            }
            return new PathPartAndTrailMatch(bestMatch, pathCategoryIds);
        }
        return null;
    }

    /**
     * Does top cat check and precision check, but assumes size was already checked.
     */
    private boolean isFirstTrailIsBetterMatchThanSecondTopCatPrecision(PathPartMatch firstMatch, List<String> firstTrail, PathPartMatch secondMatch, List<String> secondTrail, Set<String> topCategoryIds) {
        if (UtilValidate.isNotEmpty(firstTrail)) {
            if (UtilValidate.isNotEmpty(secondTrail)) {
                // 1) prefer the trail that is lower ProdCatalogCategory sequenceNum
                String firstTopCatId = firstTrail.get(0);
                String secondTopCatId = secondTrail.get(0);
                if (!firstTopCatId.equals(secondTopCatId)) {
                    for(String topCatId : topCategoryIds) {
                        // first to hit returns
                        if (firstTopCatId.equals(topCatId)) {
                            return true;
                        } else if (secondTopCatId.equals(topCatId)) {
                            return false;
                        }
                    }
                }
            } else {
                return true;
            }
        } else {
            if (UtilValidate.isNotEmpty(secondTrail)) {
                return false;
            }
        }
        // fallback on precision check
        return firstMatch.isMorePreciseThan(secondMatch);
    }



    /**
     * Checks if matches suffix and has starting slash; removes starting and ending slashes.
     * Returns null if bad.
     * Result splits cleanly on "/".
     */
    public String preprocessInboundSeoCatalogUrlPath(String pathInfo) {
        // path must start with a slash, and remove it
        if (!pathInfo.startsWith("/")) return null;
        pathInfo = pathInfo.substring(1);
        // if path ends with slash (for whatever reason it was added), remove it
        if (pathInfo.endsWith("/")) pathInfo = pathInfo.substring(0, pathInfo.length() - 1);
        return pathInfo;
    }

    /**
     * Uses the passed path elements (resolved) to try to select the best of the possible trails.
     * Returns null only if nothing matches at all.
     * BEST-EFFORT.
     * <p>
     * TODO: REVIEW: the possibility of each path elem matching multiple category IDs makes this extremely
     * complicated; so we ignore the specific implications and just match as much as possible.
     * <p>
     * For a trail to be selected, it must "end with" the pathElems; after that, the best trail is one that
     * has smallest length.
     */
    protected List<String> findBestTrailForUrlPathElems(Delegator delegator, List<List<String>> possibleTrails, List<PathPartMatches> pathElems) throws GenericEntityException {
        if (pathElems.isEmpty()) return null;
        List<String> bestTrail = null;
        for(List<String> trail : possibleTrails) {
            if (pathElems.size() > trail.size()) continue; // sure to fail

            ListIterator<PathPartMatches> pit = pathElems.listIterator(pathElems.size());
            ListIterator<String> tit = trail.listIterator(trail.size());
            boolean matched = true;
            while(matched && pit.hasPrevious()) {
                PathPartMatches urlInfos = pit.previous();
                String categoryId = tit.previous();

                // simplistic check: ignores exact vs name-only matches, but may be good enough
                if (!urlInfos.containsKey(categoryId)) {
                    matched = false;
                }
            }
            if (matched) {
                if (trail.size() == pathElems.size()) { // ideal case
                    bestTrail = trail;
                    break;
                } else if (bestTrail == null || trail.size() < bestTrail.size()) { // smaller = better
                    bestTrail = trail;
                }
            }
        }
        return bestTrail;
    }

    /*
     * *****************************************************
     * Alternative URL individual path elem part parsing
     * *****************************************************
     */

    public static class PathPartMatchOptions implements Serializable {
        public static final PathPartMatchOptions ALL = new PathPartMatchOptions(false, false, true, true);
        public static final PathPartMatchOptions ALL_NAMEONLY = new PathPartMatchOptions(false, false, false, true);
        public static final PathPartMatchOptions IDONLY = new PathPartMatchOptions(true, false, true, false);

        private final boolean exactOnly;
        private final boolean singleExactOnly; // NOTE: there is a 0.001% chance of multiple exact matches; slightly safer if this is always set to false
        private final boolean allowIdOnlyMatch;
        private final boolean allowNameMatch;
        private final String baseCacheKey;

        protected PathPartMatchOptions(boolean exactOnly, boolean singleExactOnly, boolean allowIdOnlyMatch, boolean allowNameMatch) {
            this.exactOnly = exactOnly;
            this.singleExactOnly = singleExactOnly;
            this.allowIdOnlyMatch = allowIdOnlyMatch;
            this.allowNameMatch = allowNameMatch;
            this.baseCacheKey = (exactOnly ? "Y" : "N") + ":" + (singleExactOnly ? "Y" : "N") + ":" + (allowIdOnlyMatch ? "Y" : "N") + ":" + (allowNameMatch ? "Y" : "N");
        }

        public boolean isExactOnly() { return exactOnly; }
        public boolean isSingleExactOnly() { return singleExactOnly; }
        public boolean isAllowIdOnlyMatch() { return allowIdOnlyMatch; }
        public boolean isAllowNameMatch() { return allowNameMatch; }
        public String getCacheKey() { return getBaseCacheKey(); }
        protected String getBaseCacheKey() { return baseCacheKey; }
    }

    /**
     * The results of parsing an alt URL part/path element - one or more products/categories.
     * <p>
     * The Map interface maps ID to the part info.
     * WARN: The Map interface only allows one result per ID; it's technically possible
     * to have more than one result per ID.
     */
    public static class PathPartMatches implements Map<String, PathPartMatch>, Serializable {
        private final String pathPart;
        private final Map<String, PathPartMatch> idMap;
        private final PathPartMatch single; // optimization: majority of cases

        /**
         * WARN: must be a HashMap (if size <= 1) or LinkedHashMap (if size >= 2) and not reused elsewhere (opt).
         */
        public PathPartMatches(String pathPart, Map<String, PathPartMatch> idMap) {
            this.pathPart = pathPart;
            this.idMap = idMap;
            this.single = (idMap.size() == 1) ? idMap.values().iterator().next() : null;
        }

        /**
         * WARN: must be a HashMap and not reused (opt).
         */
        public PathPartMatches(PathPartMatch single) {
            this.pathPart = single.getPathPart();
            Map<String, PathPartMatch> idMap = new HashMap<>();
            idMap.put(single.getId(), single);
            this.idMap = idMap;
            this.single = single;
        }

        /**
         * The path we matched against (*should* be the same for all the PathPartMatch of a PathPartMatches; if not, would be null).
         */
        public String getPathPart() {
            return pathPart;
        }
        /**
         * Returns single result or null if there are zero or multiple.
         */
        public PathPartMatch getSingle() {
            return single;
        }
        /**
         * WARN: may not return the original first DB result.
         */
        public PathPartMatch getFirst() {
            return (idMap.size() >= 1) ? idMap.values().iterator().next() : null;
        }

        /**
         * NOTE: this is now used post-cache in order to lessen the cache.
         * The fastest case is when (exactOnly==false && allowIdOnlyMatch==true).
         */
        public PathPartMatches filterResults(PathPartMatchOptions matchOptions) {
            if (matchOptions.isExactOnly()) {
                Map<String, PathPartMatch> newIdMap = (idMap.size() <= 1) ? new HashMap<>() : new LinkedHashMap<>();
                if (matchOptions.isAllowIdOnlyMatch()) {
                    for(Map.Entry<String, PathPartMatch> entry : idMap.entrySet()) {
                        if (entry.getValue().isExact()) {
                            newIdMap.put(entry.getKey(), entry.getValue());
                        }
                    }
                } else {
                    for(Map.Entry<String, PathPartMatch> entry : idMap.entrySet()) {
                        if (entry.getValue().isFullMatch()) {
                            newIdMap.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                return newIdMap.isEmpty() ? null : new PathPartMatches(getPathPart(), newIdMap);
            } else {
                if (matchOptions.isAllowIdOnlyMatch()) {
                    return this;
                } else {
                    Map<String, PathPartMatch> newIdMap = (idMap.size() <= 1) ? new HashMap<>() : new LinkedHashMap<>();
                    for(Map.Entry<String, PathPartMatch> entry : idMap.entrySet()) {
                        if (!entry.getValue().isIdOnlyMatch()) {
                            newIdMap.put(entry.getKey(), entry.getValue());
                        }
                    }
                    return newIdMap.isEmpty() ? null : new PathPartMatches(getPathPart(), newIdMap);
                }
            }
        }

        /**
         * @deprecated wrote this but safer not to do this
         */
        @Deprecated
        public PathPartMatch getSingleOrExact() {
            if (single != null) {
                return single;
            } else {
                // NOTE: this is clumsy, should have made a class instead of Map<String, PathPartMatch>
                PathPartMatch lastExactInfo = null;
                for(PathPartMatch info : idMap.values()) {
                    if (info.isExact()) {
                        if (lastExactInfo != null) return null; // more than one exact - no good
                        lastExactInfo = info;
                    }
                }
                return lastExactInfo;
            }
        }

        @Override public int size() { return idMap.size(); }
        @Override public boolean isEmpty() { return idMap.isEmpty(); }
        @Override public boolean containsKey(Object key) { return idMap.containsKey(key); }
        @Override public boolean containsValue(Object value) { return idMap.containsValue(value); }
        @Override public PathPartMatch get(Object key) { return idMap.get(key); }
        @Override public PathPartMatch put(String key, PathPartMatch value) { throw new UnsupportedOperationException(); }
        @Override public PathPartMatch remove(Object key) { throw new UnsupportedOperationException(); }
        @Override public void putAll(Map<? extends String, ? extends PathPartMatch> m) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public Set<String> keySet() { return Collections.unmodifiableMap(idMap).keySet(); }
        @Override public Collection<PathPartMatch> values() { return Collections.unmodifiableMap(idMap).values(); }
        @Override public Set<java.util.Map.Entry<String, PathPartMatch>> entrySet() { return Collections.unmodifiableMap(idMap).entrySet(); }
    }

    /** Factory method for PathPartMatch. **/
    public PathPartMatch makePathPartMatch(Delegator delegator, CatalogUrlType entityType, String pathPart, PathPartMatchOptions matchOptions, Timestamp moment, boolean exact, boolean idOnlyMatch, String id, String name, String localeString) {
        return new PathPartMatch(entityType, pathPart, matchOptions, moment, exact, idOnlyMatch, id, name, localeString);
    }

    /**
     * A single alt url segment info (product or catalog).
     * Incoming full alt URL with categories becomes a list of these.
     */
    public static class PathPartMatch implements Serializable {
        protected final CatalogUrlType entityType;
        protected final String pathPart;
        protected final PathPartMatchOptions matchOptions;
        protected final Timestamp moment;
        protected final boolean exact;
        protected final boolean idOnlyMatch;
        protected final String id;
        protected final String name;
        protected final String localeString;

        protected PathPartMatch(CatalogUrlType entityType, String pathPart, PathPartMatchOptions matchOptions, Timestamp moment, boolean exact, boolean idOnlyMatch, String id, String name, String localeString) {
            this.entityType = entityType;
            this.pathPart = pathPart;
            this.matchOptions = matchOptions;
            this.moment = moment;
            this.exact = exact;
            this.idOnlyMatch = idOnlyMatch;
            this.id = id;
            this.name = name;
            this.localeString = localeString;
        }

        /**
         * The type of target we found for this link: product or catalog.
         */
        public CatalogUrlType getEntityType() { return entityType; }
        /**
         * The path we matched against (*should* be the same for all the PathPartMatch of a PathPartMatches).
         */
        public String getPathPart() { return pathPart; }
        /**
         * The extraction options we used to produce this match.
         */
        public PathPartMatchOptions getMatchOptions() { return matchOptions; }
        /**
         * The timestamp we used to produce this match.
         */
        public Timestamp getMoment() { return moment; }
        /**
         * The ID from DB (NOT from the orig URL).
         */
        public String getId() { return id; }
        /**
         * The name from DB (NOT from the orig URL).
         */
        public String getName() { return name; }
        /**
         * The locale from DB or null if none.
         */
        public String getLocaleString() { return localeString; }

        /**
         * true if we had an ID match; false if name-only match.
         */
        public boolean isExact() { return exact; }

        /**
         * true if we had an ID match without name match.
         * NOTE: This refers to what part of the URL was matched - this may be true even if there was name in the URL.
         */
        public boolean isIdOnlyMatch() { return idOnlyMatch; }

        /**
         * true if full name+id match.
         */
        public boolean isFullMatch() { return exact && idOnlyMatch; }

        /**
         * NOTE: for our purposes, we consider name+id more precise than id only.
         */
        public boolean isMorePreciseThan(PathPartMatch other) {
            if (other == null) return true;
            else if (!this.exact) return false; // we are name-only (lowest precision)
            else return (!other.exact) || (!this.idOnlyMatch && other.idOnlyMatch);
        }

        public PathPartMatches getAsResult() { return new PathPartMatches(this); }
    }

    /**
     * SCIPIO: Tries to match an alt URL path element to a product and caches the results IF they match.
     * Heavily modified logic from CatalogUrlFilter.
     */
    public PathPartMatches matchPathPartProductCached(Delegator delegator, String pathPart, PathPartMatchOptions matchOptions, Timestamp moment) throws GenericEntityException {
        String key = delegator.getDelegatorName() + "::" + pathPart + "::" + matchOptions.getCacheKey();
        PathPartMatches results = productAltUrlPartInfoCache.get(key);
        if (results == null) {
            results = matchPathPartProduct(delegator, pathPart, matchOptions, moment);
            // NOTE: currently, only storing in cache if has match...
            // this is tradeoff of memory vs misses (risky to allow empty due to incoming from public)
            if (!results.isEmpty()) {
                productAltUrlPartInfoCache.put(key, results);
            }
        }
        return results.filterResults(matchOptions);
    }

    /**
     * SCIPIO: Tries to match an alt URL path element to a product.
     * Heavily modified logic from CatalogUrlFilter.
     * <p>
     * Added 2017-11-08.
     */
    public PathPartMatches matchPathPartProduct(Delegator delegator, String pathPart, PathPartMatchOptions matchOptions, Timestamp moment) throws GenericEntityException {
        Map<String, PathPartMatch> results = new LinkedHashMap<>();
        matchPathPartProductImpl(delegator, pathPart, matchOptions, moment, results);
        return new PathPartMatches(pathPart, results);
    }

    /**
     * Extracts product ID from alt URL path element using any means applicable (core implementation/override), into the passed results map.
     * Returns non-null (only) if an exact match was found, which is also added to the passed results map.
     */
    protected PathPartMatch matchPathPartProductImpl(Delegator delegator, String pathPart, PathPartMatchOptions matchOptions, Timestamp moment, Map<String, PathPartMatch> results) throws GenericEntityException {
        if (matchOptions.isAllowNameMatch()) {
            PathPartMatch exactResult = matchPathPartProductByAltUrl(delegator, pathPart, matchOptions, moment, results);
            if (exactResult != null) {
                return exactResult;
            }
        }
        if (matchOptions.isAllowIdOnlyMatch()) {
            return matchPathPartProductById(delegator, pathPart, matchOptions, moment, results);
        }
        return null;
    }

    /**
     * Extracts product ID from alt URL path element, treating it as an Alternative URL (legacy definition), into the passed results map.
     * Returns non-null only if an exact match was found, which is also added to the passed results map.
     */
    protected PathPartMatch matchPathPartProductByAltUrl(Delegator delegator, String pathPart, PathPartMatchOptions matchOptions, Timestamp moment, Map<String, PathPartMatch> results) throws GenericEntityException {
        PathPartMatch exactResult;
        // SCIPIO: this is a new filter that narrows down results from DB, which otherwise may be huge.
        EntityCondition matchTextIdCond = makeAltUrlTextIdMatchCombinations(pathPart, "productId", "textData", matchOptions.isExactOnly());
        EntityCondition contentTypeIdCond = EntityCondition.makeCondition("productContentTypeId", "ALTERNATIVE_URL");
        List<EntityCondition> condList;

        // Search for localized alt urls
        condList = new ArrayList<>();
        condList.add(contentTypeIdCond);
        condList.add(EntityCondition.makeCondition("contentAssocTypeId", "ALTERNATE_LOCALE"));
        condList.add(matchTextIdCond);
        List<GenericValue> productContentInfos = EntityQuery.use(delegator).from("ProductContentAssocAndElecTextShort")
                .where(condList).select("productId", "textData", "localeString")
                .filterByDate(moment) // cannot do this, only one filter at a time (bug): .filterByDate(moment, "caFromDate", "caThruDate")
                .orderBy("-fromDate", "-caFromDate").cache(true).queryList();
        productContentInfos = EntityUtil.filterByDate(productContentInfos, moment, "caFromDate", "caThruDate", true);
        exactResult = matchPathPartAltUrl(delegator, pathPart, productContentInfos, "productId", CatalogUrlType.PRODUCT, matchOptions, moment, results);
        if (exactResult != null) {
            return exactResult;
        }

        // Search for non-localized alt urls
        condList = new ArrayList<>();
        condList.add(contentTypeIdCond);
        condList.add(matchTextIdCond);
        productContentInfos = EntityQuery.use(delegator).from("ProductContentAndElecTextShort")
                .where(condList).select("productId", "textData", "localeString")
                .filterByDate(moment)
                .orderBy("-fromDate").cache(true).filterByDate().queryList();
        exactResult = matchPathPartAltUrl(delegator, pathPart, productContentInfos, "productId", CatalogUrlType.PRODUCT, matchOptions, moment, results);
        if (exactResult != null) {
            return exactResult;
        }
        return null;
    }

    /**
     * Extracts product ID from alt URL path element, treating it as product ID only, into the passed results map.
     * Returns non-null only if an exact match was found, which is also added to the passed results map.
     * NOTE: This will skip returning a match if the results map already contains an exact match, but will replace a previous non-exact match.
     */
    protected PathPartMatch matchPathPartProductById(Delegator delegator, String pathPart, PathPartMatchOptions matchOptions, Timestamp moment, Map<String, PathPartMatch> results) throws GenericEntityException {
        GenericValue product = EntityQuery.use(delegator).from("Product").where("productId", pathPart).cache(true).queryOne();
        if (product != null) {
            String productId = product.getString("productId");
            // this case has higher prio over non-exact match, but lower prio than alt url exact match
            PathPartMatch prevMatch = results.get(productId);
            if (prevMatch == null || !prevMatch.isExact()) {
                PathPartMatch idMatch = makePathPartMatch(delegator, CatalogUrlType.PRODUCT, pathPart, matchOptions, moment, true, true, productId, pathPart, null);
                results.put(productId, idMatch);
                return idMatch;
            }
        }
        return null;
    }

    /**
     * SCIPIO: Tries to match an alt URL path element to a category and caches the results IF they match.
     * Heavily modified logic from CatalogUrlFilter.
     */
    public PathPartMatches matchPathPartCategoryCached(Delegator delegator, String pathPart, PathPartMatchOptions matchOptions, Timestamp moment) throws GenericEntityException {
        String key = delegator.getDelegatorName() + "::" + pathPart + "::" + matchOptions.getCacheKey();
        PathPartMatches results = categoryAltUrlPartInfoCache.get(key);
        if (results == null) {
            results = matchPathPartCategory(delegator, pathPart, matchOptions, moment);
            // NOTE: currently, only storing in cache if has match...
            // this is tradeoff of memory vs misses (risky to allow empty due to incoming from public)
            if (!results.isEmpty()) {
                categoryAltUrlPartInfoCache.put(key, results);
            }
        }
        return results.filterResults(matchOptions);
    }

    /**
     * SCIPIO: Tries to match an alt URL path element to a category and caches the results IF they match.
     * Heavily modified logic from CatalogUrlFilter.
     */
    public List<PathPartMatches> matchPathPartCategoriesCached(Delegator delegator, Collection<String> pathParts, PathPartMatchOptions matchOptions, Timestamp moment) throws GenericEntityException {
        List<PathPartMatches> result = new ArrayList<>();
        for(String pathPart : pathParts) {
            result.add(matchPathPartCategoryCached(delegator, pathPart, matchOptions, moment));
        }
        return result;
    }

    /**
     * SCIPIO: Tries to match an alt URL path element to a category.
     * Heavily modified logic from CatalogUrlFilter.
     * <p>
     * Added 2017-11-07.
     */
    public PathPartMatches matchPathPartCategory(Delegator delegator, String pathPart, PathPartMatchOptions matchOptions, Timestamp moment) throws GenericEntityException {
        Map<String, PathPartMatch> results = new LinkedHashMap<>();
        matchPathPartCategoryImpl(delegator, pathPart, matchOptions, moment, results);
        return new PathPartMatches(pathPart, results);
    }

    /**
     * Extracts category ID from alt URL path element using any means applicable (core implementation/override), into the passed results map.
     * Returns non-null only if an exact match was found, which is also added to the passed results map.
     */
    protected PathPartMatch matchPathPartCategoryImpl(Delegator delegator, String pathPart, PathPartMatchOptions matchOptions, Timestamp moment, Map<String, PathPartMatch> results) throws GenericEntityException {
        if (matchOptions.isAllowNameMatch()) {
            PathPartMatch exactResult = matchPathPartCategoryByAltUrl(delegator, pathPart, matchOptions, moment, results);
            if (exactResult != null) {
                return exactResult;
            }
        }
        if (matchOptions.isAllowIdOnlyMatch()) {
            return matchPathPartCategoryById(delegator, pathPart, matchOptions, moment, results);
        }
        return null;
    }

    /**
     * Extracts category ID from alt URL path element, treating it as an Alternative URL (legacy definition), into the passed results map.
     * Returns non-null only if an exact match was found, which is also added to the passed results map.
     */
    protected PathPartMatch matchPathPartCategoryByAltUrl(Delegator delegator, String pathPart, PathPartMatchOptions matchOptions, Timestamp moment, Map<String, PathPartMatch> results) throws GenericEntityException {
        PathPartMatch exactResult;
        // SCIPIO: this is a new filter that narrows down results from DB, which otherwise may be huge.
        EntityCondition matchTextIdCond = makeAltUrlTextIdMatchCombinations(pathPart, "productCategoryId", "textData", matchOptions.isExactOnly());
        EntityCondition contentTypeIdCond = EntityCondition.makeCondition("prodCatContentTypeId", "ALTERNATIVE_URL");
        List<EntityCondition> condList;

        // Search for localized alt urls
        condList = new ArrayList<>();
        condList.add(contentTypeIdCond);
        condList.add(EntityCondition.makeCondition("contentAssocTypeId", "ALTERNATE_LOCALE"));
        condList.add(matchTextIdCond);
        List<GenericValue> productCategoryContentInfos = EntityQuery.use(delegator).from("ProductCategoryContentAssocAndElecTextShort")
                .where(condList).select("productCategoryId", "textData", "localeString")
                .filterByDate(moment) // cannot do this, only one filter at a time (bug): .filterByDate(moment, "caFromDate", "caThruDate")
                .orderBy("-fromDate", "-caFromDate").cache(true).queryList();
        productCategoryContentInfos = EntityUtil.filterByDate(productCategoryContentInfos, moment, "caFromDate", "caThruDate", true);
        exactResult = matchPathPartAltUrl(delegator, pathPart, productCategoryContentInfos, "productCategoryId", CatalogUrlType.CATEGORY, matchOptions, moment, results);
        if (exactResult != null) {
            return exactResult;
        }

        // Search for non-localized alt urls
        condList = new ArrayList<>();
        condList.add(contentTypeIdCond);
        condList.add(matchTextIdCond);
        productCategoryContentInfos = EntityQuery.use(delegator).from("ProductCategoryContentAndElecTextShort")
                .where(condList).select("productCategoryId", "textData", "localeString")
                .filterByDate(moment)
                .orderBy("-fromDate").cache(true).queryList();
        return matchPathPartAltUrl(delegator, pathPart, productCategoryContentInfos, "productCategoryId", CatalogUrlType.CATEGORY, matchOptions, moment, results);
    }

    /**
     * Extracts category ID from alt URL path element, treating it as category ID only, into the passed results map.
     * Returns non-null only if an exact match was found, which is also added to the passed results map.
     * NOTE: This will skip returning a match if the results map already contains an exact match, but will replace a previous non-exact match.
     */
    protected PathPartMatch matchPathPartCategoryById(Delegator delegator, String pathPart, PathPartMatchOptions matchOptions, Timestamp moment, Map<String, PathPartMatch> results) throws GenericEntityException {
        GenericValue productCategory = EntityQuery.use(delegator).from("ProductCategory").where("productCategoryId", pathPart).cache(true).queryOne();
        if (productCategory != null) {
            String productCategoryId = productCategory.getString("productCategoryId");
            // this case has higher prio over non-exact match, but lower prio than alt url exact match
            PathPartMatch prevMatch = results.get(productCategoryId);
            if (prevMatch == null || !prevMatch.isExact()) {
                PathPartMatch idMatch = makePathPartMatch(delegator, CatalogUrlType.CATEGORY, pathPart, matchOptions, moment,true, true, productCategoryId, pathPart, null);
                results.put(productCategoryId, idMatch);
                return idMatch;
            }
        }
        return null;
    }

    /**
     * This splits pathPart by hyphen "-" and creates OR condition for all the possible combinations
     * of name and ID.
     */
    public static EntityCondition makeAltUrlTextIdMatchCombinations(String pathPart, String idField, String textField, boolean exactOnly) {
        List<EntityCondition> condList = new ArrayList<>();
        int lastIndex = pathPart.lastIndexOf('-');
        while(lastIndex > 0) {
            String name = pathPart.substring(0, lastIndex);
            String id = pathPart.substring(lastIndex + 1);
            if (!id.isEmpty()) { // 2019-08-09: if ID is empty, we have a URL that ends with a dash ('-'); covered for backward-compat below
                condList.add(EntityCondition.makeCondition(
                        EntityCondition.makeCondition(textField, EntityOperator.LIKE, name),
                        EntityOperator.AND,
                        EntityCondition.makeCondition(idField, id)));
            }
            lastIndex = pathPart.lastIndexOf('-', lastIndex - 1);
        }
        if (!exactOnly) {
            // try one without any ID
            condList.add(EntityCondition.makeCondition(textField, EntityOperator.LIKE, pathPart));
        }
        return EntityCondition.makeCondition(condList, EntityOperator.OR);
    }

    /**
     * Finds ALTERNATIVE_URL matches and adds to results map. If an exact match is found, returns immediately
     * with the result. If no exact, returns null.
     * Based on CatalogUrlFilter iteration.
     * <p>
     * NOTE: 2017: the singleExactOnly flag was initially going to be important for performance reasons,
     * but it can now be left to false (which is 0.01% safer) as long as the caller uses
     * {@link #makeAltUrlTextIdMatchCombinations} in the values query.
     */
    private PathPartMatch matchPathPartAltUrl(Delegator delegator, String pathPart, List<GenericValue> values, String idField,
                                              CatalogUrlType entityType, PathPartMatchOptions matchOptions, Timestamp moment, Map<String, PathPartMatch> results) {
        for (GenericValue value : values) {
            String textData = value.getString("textData");
            // TODO: REVIEW: if we didn't have to sanitize the DB records, it could
            // allow some types of LIKE DB queries, so try to do without sanitize if possible...
            // SCIPIO: NOTE: assuming DB data good as-is for now - this loop could become very slow...
            //getCatalogAltUrlSanitizer().sanitizeAltUrlFromDb(textData, null, entityType);
            //textData = UrlServletHelper.invalidCharacter(textData);

            if (pathPart.startsWith(textData)) {
                String pathPartIdStr = pathPart.substring(textData.length());
                String valueId = value.getString(idField);
                if (pathPartIdStr.isEmpty()) {
                    if (!matchOptions.isExactOnly()) {
                        // id omitted - add to results, but don't stop looking
                        if (!results.containsKey(valueId)) { // don't replace in case exact match (don't need check)
                            results.put(valueId, makePathPartMatch(delegator, entityType, pathPart, matchOptions, moment, false, false, valueId, textData, value.getString("localeString")));
                        }
                    }
                } else {
                    if (pathPartIdStr.startsWith("-")) { // should always be a hyphen here
                        pathPartIdStr = pathPartIdStr.substring(1);
                        if (pathPartIdStr.equalsIgnoreCase(valueId)) {
                            PathPartMatch urlInfo = makePathPartMatch(delegator, entityType, pathPart, matchOptions, moment, true, false, valueId, textData, value.getString("localeString"));
                            if (matchOptions.isSingleExactOnly()) {
                                results.clear();
                                results.put(valueId, urlInfo);
                                return urlInfo;
                            } else {
                                results.put(valueId, urlInfo);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /*
     * *****************************************************
     * Outbound matching
     * *****************************************************
     */

    private static final Pattern absUrlPat = Pattern.compile("(((.*?):)?//([^/]*))?(.*)");

    /**
     * Checks an outbound URL for /control/product, /control/category or other
     * such request and tries to extract the IDs.
     * FIXME: does not preserve trail, uses default always. Also didn't fully abstract this yet.
     * FIXME: cannot handle jsessionid
     */
    public String matchReplaceOutboundSeoTranslatableUrl(HttpServletRequest request, Delegator delegator, String url, String productReqPath, String categoryReqPath, String contextRoot) {
        if (url == null) return null;
        // pre-filters for speed
        if (url.contains(productReqPath) || url.contains(categoryReqPath)) {
            // Extract the relative URL from absolute
            Matcher mrel = absUrlPat.matcher(url);
            if (mrel.matches()) {

                String absPrefix = mrel.group(1);
                if (absPrefix == null) {
                    absPrefix = "";
                }
                String relUrl = mrel.group(5);

                // Check if within same webapp
                // (Note: in Ofbiz the encoded URLs contain the webapp context root in relative URLs)
                if (relUrl.startsWith((contextRoot.length() > 1) ? contextRoot + "/" : contextRoot)) {
                    String pathInfo = relUrl.substring(contextRoot.length());
                    if (pathInfo.startsWith(productReqPath)) {
                        if (pathInfo.length() > productReqPath.length()) {
                            Map<String, String> params = extractParamsFromRest(pathInfo, productReqPath.length());
                            if (params == null) return null;
                            String productId = params.remove("product_id");
                            if (productId == null) {
                                productId = params.remove("productId");
                            }
                            if (productId != null) {
                                String colonString = params.remove("colonString");
                                if (colonString == null) colonString = "";

                                Locale locale = UtilHttp.getLocale(request); // FIXME?: sub-ideal
                                String newUrl = makeProductUrl(request, locale, null, null, productId);
                                if (newUrl == null) return null;

                                String remainingParams = makeRemainingParamsStr(params, pathInfo.contains("&amp;"), !newUrl.contains("?"));

                                return absPrefix + newUrl + colonString + remainingParams;
                            }
                        }
                    } else if (pathInfo.startsWith(categoryReqPath)) {
                        if (pathInfo.length() > categoryReqPath.length()) {
                            Map<String, String> params = extractParamsFromRest(pathInfo, categoryReqPath.length());
                            if (params == null) return null;
                            String categoryId = params.remove("category_id");
                            if (categoryId == null) {
                                categoryId = params.remove("productCategoryId");
                            }
                            if (categoryId != null) {
                                String colonString = params.remove("colonString");
                                if (colonString == null) colonString = "";

                                Locale locale = UtilHttp.getLocale(request); // FIXME?: sub-ideal
                                // FIXME miss view size
                                String newUrl = makeCategoryUrl(request, locale, null, categoryId, null, null, null, null, null);
                                if (newUrl == null) return null;

                                String remainingParams = makeRemainingParamsStr(params, pathInfo.contains("&amp;"), !newUrl.contains("?"));

                                return absPrefix + newUrl + colonString + remainingParams;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static final Pattern paramPat = Pattern.compile("&amp;|&");

    private static Map<String, String> extractParamsFromRest(String pathInfo, int index) {
        char nextChar = pathInfo.charAt(index);
        String queryString;
//        String colonString;
        if (nextChar == '?') {
            // FIXME: can't handle colon parameters
//            colonString = null;
            queryString = pathInfo.substring(index + 1);
//            int colonIndex = pathInfo.indexOf(';', index + 1);
//            if (colonIndex >= 0) {
//                colonString = pathInfo.substring(colonIndex);
//                queryString = pathInfo.substring(index + 1, colonIndex);
//            } else {
//                queryString = pathInfo.substring(index + 1);
//                colonString = null;
//            }
//        } else if (nextChar == ';') {
//            int colonIndex = nextChar;
//            index = pathInfo.indexOf('?', index+1);
//            if (index < 0) return new HashMap<>();
//            colonString = pathInfo.substring(colonIndex, index);
//            queryString = pathInfo.substring(index + 1);
        } else {
            return null;
        }
        Map<String, String> params = extractParamsFromQueryString(queryString);
//        if (colonString != null) params.put("colonString", colonString);
        return params;
    }

    private static Map<String, String> extractParamsFromQueryString(String queryString) {
        String[] parts = paramPat.split(queryString);
        Map<String, String> params = new LinkedHashMap<>();
        for(String part : parts) {
            if (part.length() == 0) continue;
            int equalsIndex = part.indexOf('=');
            if (equalsIndex <= 0 || equalsIndex >= (part.length() - 1)) continue;
            params.put(part.substring(0, equalsIndex), part.substring(equalsIndex + 1));
        }
        return params;
    }

    private static String makeRemainingParamsStr(Map<String, String> params, boolean useEncoded, boolean firstParams) {
        StringBuilder sb = new StringBuilder();
        for(Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() == 0 && firstParams) {
                sb.append("?");
            } else {
                sb.append(useEncoded ? "&amp;" : "&");
            }
            sb.append(entry.getKey());
            sb.append("=");
            sb.append(entry.getValue());
        }
        return sb.toString();
    }

    /*
     * *****************************************************
     * Abstracted general queries for product/category URLs
     * *****************************************************
     * NOTE: some of these may be cacheable if needed in future.
     */

    /**
     * Returns the top categories that are valid for use when generating category URLs,
     * as ORDERED set.
     * <p>
     * 2017: NOTE: for legacy behavior, this returns all ProdCatalogCategory IDs for category URLs (only).
     */
    protected Set<String> getCatalogTopCategoriesForCategoryUrl(Delegator delegator, String currentCatalogId, String webSiteId) {
        if (currentCatalogId == null) {
            currentCatalogId = getCurrentCatalogId(delegator, currentCatalogId, webSiteId);
            if (currentCatalogId == null) return new LinkedHashSet<>();
        }
        return getProdCatalogCategoryIds(delegator, currentCatalogId, null);
    }

    /**
     * Returns the top categories that are valid for use when generating product URLs,
     * as ORDERED set.
     * <p>
     * 2017: NOTE: for legacy behavior, this currently returns only the single first top catalog category.
     * <p>
     * TODO: REVIEW: this could support more, but it may affect store browsing.
     */
    protected Set<String> getCatalogTopCategoriesForProductUrl(Delegator delegator, String currentCatalogId, String webSiteId) {
        if (currentCatalogId == null) {
            currentCatalogId = getCurrentCatalogId(delegator, currentCatalogId, webSiteId);
            if (currentCatalogId == null) return new LinkedHashSet<>();
        }
        return getProdCatalogTopCategoryId(delegator, currentCatalogId);
    }

    /**
     * Return all paths from the given topCategoryIds to the product.
     * <p>
     * TODO?: perhaps can cache with UtilCache in future, or read from a cached category tree.
     */
    protected List<List<String>> getProductRollupTrails(Delegator delegator, String productId, Set<String> topCategoryIds, boolean useCache) {
        return ProductWorker.getProductRollupTrails(delegator, productId, topCategoryIds, useCache);
    }

    /**
     * Return all paths from the given topCategoryIds to the product.
     * <p>
     * TODO?: perhaps can cache with UtilCache in future, or read from a cached category tree.
     */
    protected List<List<String>> getProductRollupTrails(Delegator delegator, String productId, Set<String> topCategoryIds) {
        return getProductRollupTrails(delegator, productId, topCategoryIds, true);
    }

    /**
     * Return all paths from the given topCategoryIds to the category.
     * <p>
     * TODO?: perhaps can cache with UtilCache in future, or read from a cached category tree.
     */
    protected List<List<String>> getCategoryRollupTrails(Delegator delegator, String productCategoryId, Set<String> topCategoryIds, boolean useCache) {
        return CategoryWorker.getCategoryRollupTrails(delegator, productCategoryId, topCategoryIds, useCache);
    }

    /**
     * Return all paths from the given topCategoryIds to the category.
     * <p>
     * TODO?: perhaps can cache with UtilCache in future, or read from a cached category tree.
     */
    protected List<List<String>> getCategoryRollupTrails(Delegator delegator, String productCategoryId, Set<String> topCategoryIds) {
        return getCategoryRollupTrails(delegator, productCategoryId, topCategoryIds, true);
    }

    /*
     * *****************************************************
     * Generic/static helpers
     * *****************************************************
     */

    /**
     * Returns all ProdCatalogCategory IDs or the types requested (null for all).
     */
    protected static Set<String> getProdCatalogCategoryIds(Delegator delegator, String prodCatalogId, Collection<String> prodCatalogCategoryTypeIds) {
        if (prodCatalogId == null || prodCatalogId.isEmpty()) return new LinkedHashSet<>();
        List<GenericValue> values = CatalogWorker.getProdCatalogCategories(delegator, prodCatalogId, null);
        Set<String> idList = new LinkedHashSet<>();
        for(GenericValue value : values) {
            if (prodCatalogCategoryTypeIds == null || prodCatalogCategoryTypeIds.contains(value.getString("prodCatalogCategoryTypeId"))) {
                idList.add(value.getString("productCategoryId"));
            }
        }
        return idList;
    }

    /**
     * Returns the single top category.
     */
    protected static Set<String> getProdCatalogTopCategoryId(Delegator delegator, String prodCatalogId) {
        String topCategoryId = CatalogWorker.getCatalogTopCategoryId(delegator, prodCatalogId);
        Set<String> topCategoryIds = new LinkedHashSet<>();
        if (topCategoryId == null) {
            Debug.logWarning("Seo: matchInboundSeoCatalogUrl: cannot determine top category for prodCatalogId '" + prodCatalogId + "'; can't select best trail", module);
        } else {
            topCategoryIds.add(topCategoryId);
        }
        return topCategoryIds;
    }

    protected static String getCurrentCatalogId(Delegator delegator, String currentCatalogId, String webSiteId) {
        if (currentCatalogId == null) {
            currentCatalogId = getWebsiteTopCatalog(delegator, webSiteId);
            if (currentCatalogId == null) {
                Debug.logWarning("Seo: Cannot determine current or top catalog for webSiteId: " + webSiteId, module);
            }
        }
        return currentCatalogId;
    }

    protected static String getWebsiteTopCatalog(Delegator delegator, String webSiteId) {
        if (UtilValidate.isEmpty(webSiteId)) {
            return null;
        }
        try {
            GenericValue webSite = EntityQuery.use(delegator).from("WebSite").where("webSiteId", webSiteId).cache(true).queryOne();
            if (webSite == null) {
                Debug.logError("getWebsiteTopCatalog: Invalid webSiteId: " + webSiteId, module);
                return null;
            }
            String productStoreId = webSite.getString("productStoreId");
            if (productStoreId == null) return null;

            List<GenericValue> storeCatalogs = CatalogWorker.getStoreCatalogs(delegator, productStoreId);
            if (UtilValidate.isNotEmpty(storeCatalogs)) {
                return storeCatalogs.get(0).getString("prodCatalogId");
            }
        } catch(Exception e) {
            Debug.logError("getWebsiteTopCatalog: error while determining catalog for webSiteId '" + webSiteId + "': " + e.getMessage(), module);
            return null;
        }
        return null;
    }

    protected static List<String> newPathList() {
        return new ArrayList<>();
    }

    protected static List<String> newPathList(int initialCapacity) {
        return new ArrayList<>(initialCapacity);
    }

    protected static List<String> newPathList(Collection<String> fromList) {
        return new ArrayList<>(fromList);
    }

    protected static List<String> ensurePathList(List<String> pathList) {
        return pathList != null ? pathList : newPathList();
    }

    /**
     * Returns the first trail having the topCategory which is the earliest possible in the topCategoryIds list,
     * or null if none of them.
     * Prefers lower ProdCatalogCategory sequenceNum.
     */
    protected static List<String> getFirstTopTrail(List<List<String>> possibleTrails, Collection<String> topCategoryIds) {
        for(String topCategoryId : topCategoryIds) { // usually just one iteration
            for(List<String> trail : possibleTrails) {
                if (trail != null && !trail.isEmpty() && topCategoryId.equals(trail.get(0))) return trail;
            }
        }
        return null;
    }

    // NOTE: if need this, there's should already be a util somewhere...
//    private static boolean pathStartsWithDir(String path, String dir) {
//        // needs delimiter logic
//        if (UtilValidate.isEmpty(path)) {
//            return UtilValidate.isEmpty(dir);
//        }
//        if (path.length() > dir.length()) {
//            if (dir.endsWith("/")) return path.startsWith(dir);
//            else return path.startsWith(dir + "/");
//        } else {
//            return path.equals(dir);
//        }
//    }

    private static <T> void removeLastIfEquals(List<T> list, T value) {
        if (list != null && list.size() > 0 && value != null && value.equals(list.get(list.size() - 1))) {
            list.remove(list.size() - 1);
        }
    }

    /**
     * Last index of, with starting index (inclusive - .get(startIndex) is compared - like String interface).
     */
    static <T> int lastIndexOf(List<T> list, T object, int startIndex) {
        ListIterator<T> it = list.listIterator(startIndex + 1);
        while(it.hasPrevious()) {
            if (object.equals(it.previous())) {
                return it.nextIndex();
            }
        }
        return -1;
    }

    static void ensureTrailingSlash(StringBuilder sb) {
        if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '/') {
            sb.append("/");
        }
    }

    static void appendSlashAndValue(StringBuilder sb, String value) {
        if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '/') {
            sb.append("/");
        }
        sb.append(value);
    }

    static void appendSlashAndValue(StringBuilder sb, Collection<String> values) {
        for(String value : values) {
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '/') {
                sb.append("/");
            }
            sb.append(value);
        }
    }

    /**
     * Maps locale to name and vice-versa - optimization to avoid ResourceBundle, which
     * has API limitations and needless lookups.
     * <p>
     * FIXME?: This has issues and might not 100% emulate the properties map behavior yet.
     */
    static class LocalizedName implements Serializable {
        private final String defaultValue;
        private final Map<String, String> localeValueMap;
        private final Map<String, Locale> valueLocaleMap;

        public LocalizedName(Map<String, String> localeValueMap, Locale defaultLocale) {
            this.localeValueMap = new HashMap<>(localeValueMap);
            Map<String, Locale> valueLocaleMap = makeValueLocaleMap(localeValueMap);
            String defaultValue = (defaultLocale != null) ? getNameForLocale(localeValueMap, defaultLocale) : null;
            if (UtilValidate.isNotEmpty(defaultValue)) {
                // 2019-08-09: re-insert the default value for default locale because otherwise it may register to another language
                valueLocaleMap.put(defaultValue, defaultLocale);
            }
            this.valueLocaleMap = valueLocaleMap;
            this.defaultValue = defaultValue;
        }

        /**
         * NOTE: the resulting mapping may not be 1-for-1 - depends on the values.
         */
        private static Map<String, Locale> makeValueLocaleMap(Map<String, String> localeValueMap) {
            Map<String, Locale> valueLocaleMap = new HashMap<>();
            for(Map.Entry<String, String> entry : localeValueMap.entrySet()) {
                // SPECIAL: duplicates *could* be possible - keep the shortest locale name (most generic)
                // for the string-to-locale mapping
                Locale prevLocale = valueLocaleMap.get(entry.getValue());
                Locale nextLocale = UtilMisc.parseLocale(entry.getKey());
                if (prevLocale != null) {
                    if (nextLocale.toString().length() < prevLocale.toString().length()) {
                        valueLocaleMap.put(entry.getValue(), nextLocale);
                    }
                } else {
                    valueLocaleMap.put(entry.getValue(), nextLocale);
                }
            }
            return valueLocaleMap;
        }

        /**
         * Tries to create an instance from a property message.
         * <p>
         * FIXME?: there must be a better way than current loop with exception on missing locale...
         */
        public static LocalizedName getNormalizedFromProperties(String resource, String name, Locale defaultLocale) {
            Map<String, String> localeValueMap = new HashMap<>();
            for(Locale locale: UtilMisc.availableLocales()) {
                ResourceBundle bundle = null;
                try {
                    bundle = UtilProperties.getResourceBundle(resource, locale);
                } catch(Exception e) {
                    continue; // when locale is not there, throws IllegalArgumentException
                }
                if (bundle == null || !bundle.containsKey(name)) continue;
                String value = bundle.getString(name);
                if (value == null) continue;
                value = value.trim();
                if (value.isEmpty()) continue;
                localeValueMap.put(locale.toString(), value);
            }
            return new LocalizedName(localeValueMap, defaultLocale);
        }

        /**
         * Tries to create an instance from a property message, with general.properties fallback
         * locale as the default locale.
         */
        public static LocalizedName getNormalizedFromProperties(String resource, String name) {
            return getNormalizedFromProperties(resource, name, UtilProperties.getFallbackLocale());
        }

        public Locale getLocaleForName(String name) {
            return valueLocaleMap.get(name);
        }

        public String getNameForLocale(Locale locale) {
            return getNameForLocale(localeValueMap, locale);
        }

        private static String getNameForLocale(Map<String, String> localeValueMap, Locale locale) {
            String value = localeValueMap.get(locale.toString());
            if (value == null) value = localeValueMap.get(normalizeLocaleStr(locale));
            return value;
        }

        public String getNameForLocaleOrDefault(Locale locale) {
            if (locale == null) return defaultValue;
            String value = getNameForLocale(locale);
            if (value == null) value = defaultValue;
            return value;
        }

        public static String normalizeLocaleStr(Locale locale) {
            return locale.getLanguage();
        }
    }
}
