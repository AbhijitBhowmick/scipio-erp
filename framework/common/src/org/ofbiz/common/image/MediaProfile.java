package org.ofbiz.common.image;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.cache.UtilCache;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.DelegatorFactory;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Represents media profile definitions, as defined in mediaprofiles.properties.
 * TODO: integrate ImageSizePreset (where mediaProfile name doubles as presetId)
 */
public abstract class MediaProfile implements Serializable {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());

    private static final UtilCache<String, Profiles> CACHE = UtilCache.createUtilCache("scipio.mediaProfile");

    private static class Profiles implements Serializable {
        final Map<String, MediaProfile> nameMap;
        final Map<String, Map<String, MediaProfile>> typeMap;
        final Map<String, MediaProfile> defaultsMap;

        Profiles(Map<String, MediaProfile> nameMap, Map<String, Map<String, MediaProfile>> typeMap, Map<String, MediaProfile> defaultsMap) {
            this.nameMap = Collections.unmodifiableMap(nameMap);
            this.typeMap = Collections.unmodifiableMap(typeMap);
            this.defaultsMap = Collections.unmodifiableMap(defaultsMap);
        }
    }

    protected final String name;
    protected final String location;
    protected final boolean defaultProfile;
    protected final Map<String, Object> properties;

    protected final String delegatorName;
    protected transient Delegator delegator;

    protected MediaProfile(Delegator delegator, String name, Map<String, Object> properties) {
        this.name = name;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.location = UtilValidate.nullIfEmpty((String) properties.get("location"));
        this.defaultProfile = UtilValidate.booleanValueVersatile(properties.get("defaultProfile"), false);
        this.delegatorName = delegator.getDelegatorName();
        this.delegator = delegator;
    }

    public static <M extends MediaProfile> M create(Delegator delegator, String name, String type, Map<String, Object> properties) {
        if ("IMAGE_OBJECT".equals(type)) {
            // TODO: unhardcode
            return UtilGenerics.cast(new ImageProfile(delegator, name, properties));
        } else {
            throw new UnsupportedOperationException("Unsupported media profile type: " + type);
        }
    }

    public static <M extends MediaProfile> M create(Delegator delegator, String name, Map<String, Object> properties) {
        return create(delegator, name, (String) properties.get("type"), properties);
    }

    public static void clearCaches(Delegator delegator) {
        if (delegator != null) {
            CACHE.remove(delegator.getDelegatorName());
        } else {
            CACHE.clear();
        }
    }

    /**
     * Gets a media profile by name or null if not specifically defined, no default fallbacks, as defined in mediaprofiles.properties.
     * NOTE: This overload does not require the type as by convention the profile names imply it ("IMAGE_DEFAULT", "IMAGE_PRODUCT", etc.).
     */
    public static <M extends MediaProfile> M getMediaProfile(Delegator delegator, String name) {
        Profiles profiles = getProfiles(delegator);
        return UtilGenerics.cast(profiles.nameMap.get(name));
    }

    /**
     * Gets a media profile by name and type or null if not specifically defined, no default fallbacks, as defined in mediaprofiles.properties.
     */
    public static <M extends MediaProfile> M getMediaProfile(Delegator delegator, String name, String type) {
        Profiles profiles = getProfiles(delegator);
        Map<String, MediaProfile> map = profiles.typeMap.get(type);
        return (map != null) ? UtilGenerics.cast(map.get(name)) : null;
    }

    /**
     * Gets a media profile by name and type or null if not specifically defined, or default profile for the type, as defined in mediaprofiles.properties.
     */
    public static <M extends MediaProfile> M getMediaProfileOrDefault(Delegator delegator, String name, String type) {
        M mediaProfile = getMediaProfile(delegator, name, type);
        return (mediaProfile != null) ? mediaProfile : getDefaultMediaProfile(delegator, type);
    }

    /**
     * Gets a default media profile by type, as defined in mediaprofiles.properties.
     */
    public static <M extends MediaProfile> M getDefaultMediaProfile(Delegator delegator, String type) {
        Profiles profiles = getProfiles(delegator);
        M mediaProfile = UtilGenerics.cast(profiles.defaultsMap.get(type));
        if (mediaProfile == null) {
            Debug.logError("No default media profile exists for media type [" + type + "] in mediaprofiles.properties; default must be defined (defaultProfile=true)", module);
        }
        return mediaProfile;
    }

    public static Collection<String> getMediaProfileNames(Delegator delegator, String type) {
        Profiles profiles = getProfiles(delegator);
        Map<String, MediaProfile> map = profiles.typeMap.get(type);
        return (map != null) ? map.keySet() : Collections.emptySet();
    }

    private static Profiles getProfiles(Delegator delegator) {
        Profiles profiles = CACHE.get(delegator.getDelegatorName());
        if (profiles == null) {
            profiles = loadProfiles(delegator);
            CACHE.put(delegator.getDelegatorName(), profiles);
        }
        return profiles;
    }

    private static Profiles loadProfiles(Delegator delegator) {
        // TODO: load from ImageSizePreset entities
        Map<String, MediaProfile> nameMap = new LinkedHashMap<>();
        Map<String, Map<String, MediaProfile>> typeMap = new LinkedHashMap<>();
        Map<String, MediaProfile> defaultsMap = new LinkedHashMap<>();
        for(MediaProfile mediaProfile : readStaticMediaProfiles(delegator, UtilProperties.getMergedPropertiesFromAllComponents("mediaprofiles")).values()) {
            nameMap.put(mediaProfile.getName(), mediaProfile);
            Map<String, MediaProfile> typeProfiles = typeMap.get(mediaProfile.getType());
            if (typeProfiles == null) {
                typeProfiles = new LinkedHashMap<>();
                typeMap.put(mediaProfile.getType(), typeProfiles);
            }
            typeProfiles.put(mediaProfile.getName(), mediaProfile);
            if (mediaProfile.isDefaultProfile()) {
                defaultsMap.put(mediaProfile.getType(), mediaProfile);
            }
        }
        for(Map.Entry<String, Map<String, MediaProfile>> entry : typeMap.entrySet()) {
            entry.setValue(Collections.unmodifiableMap(entry.getValue()));
        }
        return new Profiles(nameMap, typeMap, defaultsMap);
    }

    private static Map<String, MediaProfile> readStaticMediaProfiles(Delegator delegator, Properties mediaProfilesProps) {
        Map<String, MediaProfile> mpp = new LinkedHashMap<>();
        Map<String, Map<String, Object>> profiles = UtilProperties.extractPropertiesWithPrefixAndId(new LinkedHashMap<>(), mediaProfilesProps, "mediaProfile.");
        for(Map.Entry<String, Map<String, Object>> entry : profiles.entrySet()) {
            String name = entry.getKey();
            String type = (String) entry.getValue().get("type");
            try {
                MediaProfile mediaProfile = create(delegator, name, type, entry.getValue());
                mpp.put(name, mediaProfile);
            } catch(Exception e) {
                Debug.logError("Error initializing mediaprofile.properties profile [" + name + "] definition " + entry.getValue() + ": " + e.toString(), module);
            }
        }
        return mpp;
    }


    public abstract String getType();

    public String getName() {
        return name;
    }

    public boolean isDefaultProfile() {
        return defaultProfile;
    }

    public String getLocation() {
        return location;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    protected Delegator getDelegator() {
        Delegator delegator = this.delegator;
        if (delegator == null) {
            delegator = DelegatorFactory.getDelegator(this.delegatorName);
            this.delegator = delegator;
        }
        return delegator;
    }

}
