package com.bitlove.fetlife.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.bitlove.fetlife.BuildConfig;
import com.bitlove.fetlife.FetLifeApplication;
import com.bitlove.fetlife.R;
import com.bitlove.fetlife.model.db.FetLifeDatabase;
import com.bitlove.fetlife.model.pojos.fetlife.dbjson.Member;
import com.bitlove.fetlife.util.FileUtil;
import com.bitlove.fetlife.util.PreferenceKeys;
import com.bitlove.fetlife.util.SecurityUtil;
import com.bitlove.fetlife.util.StringUtil;
import com.bitlove.fetlife.view.screen.standalone.SettingsActivity;
import com.crashlytics.android.Crashlytics;
import com.onesignal.OneSignal;
import com.raizlabs.android.dbflow.config.FlowConfig;
import com.raizlabs.android.dbflow.config.FlowManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UserSessionManager {

    private final FetLifeApplication fetLifeApplication;

    private Member currentUser;
    private SharedPreferences activePreferences;

    public UserSessionManager(FetLifeApplication fetLifeApplication) {
        this.fetLifeApplication = fetLifeApplication;
    }

    //*** Public access methods to logged in user session info

    public synchronized Member getCurrentUser() {
        return currentUser;
    }

    public SharedPreferences getActiveUserPreferences() {
        return activePreferences;
    }


    //*** Public State Change Calls

    public void init() {

        applyVersionUpgrade();

        String lastLoggedInUserId = getLastLoggedInUserId();

        if (lastLoggedInUserId == null) {
            return;
        }

        loadUserPreferences(lastLoggedInUserId);
        if(isUserLoggedIn() && !keepUserSignedIn()) {
            setupUserSession(lastLoggedInUserId);
            logOutUser();
        } else if (isUserLoggedIn()) {
            setupUserSession(lastLoggedInUserId);
        } else if (keepUserSignedIn()) {
            logInUser(lastLoggedInUserId, null);
        } else {
            activePreferences = null;
        }
    }

    private void applyVersionUpgrade() {
        SharedPreferences mainPreferences = PreferenceManager.getDefaultSharedPreferences(fetLifeApplication);
        int lastVersionUpgrade = mainPreferences.getInt(PreferenceKeys.MAIN_PREF_KEY_LAST_VERSION_UPGRADE,0);
        if (lastVersionUpgrade < 20622) {
            SharedPreferences.Editor preferenceEditor = mainPreferences.edit();
            if (lastVersionUpgrade != 0) {
                preferenceEditor.putInt(PreferenceKeys.MAIN_PREF_KEY_LAST_VERSION_NOTIFICATION,lastVersionUpgrade);
            }
            try {
                resetDb();
            } catch (Exception e) {
                Crashlytics.logException(e);
            }
            preferenceEditor.putInt(PreferenceKeys.MAIN_PREF_KEY_LAST_VERSION_UPGRADE,fetLifeApplication.getVersionNumber()).apply();
        }
        //TODO: migrate user preferences
        //TODO: set default preference applience fr new default setting appliance
        //TODO: remove all previous DB file
        //TODO: remove previous user history
    }

    public synchronized void onUserLogIn(Member loggedInUser, boolean autoLogin) {
        applyVersionUpgrade();

        if (currentUser != null && currentUser.getId().equals(loggedInUser.getId())) {
            updateUserRecord(loggedInUser);
        } else {
            logInUser(loggedInUser.getId(),loggedInUser);
        }
        setKeepUserSignedIn(autoLogin);
    }

    public synchronized void onUserLogOut() {
        logOutUser();
    }

    public synchronized void onUserReset() {
        if (currentUser == null) {
            return;
        }
        deleteCurrentUser();
        logOutUser();
    }

    //*** Session State Methods

    private void logInUser(String userId, Member userRecord) {
        stopDb();
        loadUserPreferences(userId);
        loadUserDb(userId);
        setUserLoggedIn(true);
        startDb();
        if (userRecord != null) {
            updateUserRecord(userRecord);
        } else {
            currentUser = loadUserRecord(userId);
            if (currentUser == null) {
                logOutUser();
                return;
            }
        }
        updateUserMetaInfo(userId);
        registerToPushMessages(currentUser);
    }

    private void logOutUser() {
        stopDb();
        clearUserPreferences();
        setUserLoggedIn(false);
        currentUser = null;
    }

    private void deleteCurrentUser() {
        unregisterFromPushMessages(currentUser);
        deleteUserMetaInfo(currentUser.getId());
        deletedUserPreference(currentUser.getId());
        deleteCurrentUserDb();
    }

    private void setupUserSession(String userId) {
        loadUserDb(userId);
        startDb();
        currentUser = loadUserRecord(userId);
    }

    //*** User Preferences managing Calls

    private static final String USER_PREF_KEY_GENERAL_PREFERNCES_APPLIED = "USER_PREF_KEY_GENERAL_PREFERNCES_APPLIED";
    private static final String USER_PREF_KEY_NOTIFICATION_PREFERNCES_APPLIED = "USER_PREF_KEY_NOTIFICATION_PREFERNCES_APPLIED";
    private static final String USER_PREF_KEY_PROFILE_PREFERNCES_APPLIED = "USER_PREF_KEY_PROFILE_PREFERNCES_APPLIED";
    private static final String USER_PREF_KEY_FEED_PREFERENCES_APPLIED = "USER_PREF_KEY_FEED_PREFERENCES_APPLIED";

    private void loadUserPreferences(String userId) {
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Loading user preferences for " + userId);
        }
        String userPreferenceName = getUserPreferenceName(userId);
        SettingsActivity.init(userPreferenceName);

        activePreferences =  fetLifeApplication.getSharedPreferences(userPreferenceName,Context.MODE_PRIVATE);
        if (activePreferences == null) {
            if (BuildConfig.DEBUG) {
                Log.e("UserSession","User preferences could not be loaded");
            }
            Crashlytics.logException(new Exception("User preferences could not be loaded"));
        }

        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Loaded preferences contains " + activePreferences.getAll().size() + " preferences");
        }

        if (!activePreferences.getBoolean(USER_PREF_KEY_GENERAL_PREFERNCES_APPLIED, false)) {
            if (BuildConfig.DEBUG) {
                Log.d("UserSession","Applying default preferences for general preferences");
            }
            PreferenceManager.setDefaultValues(fetLifeApplication, userPreferenceName, Context.MODE_PRIVATE, R.xml.general_preferences, false);
            activePreferences.edit().putBoolean(USER_PREF_KEY_GENERAL_PREFERNCES_APPLIED, true);
        }

        if (!activePreferences.getBoolean(USER_PREF_KEY_NOTIFICATION_PREFERNCES_APPLIED, false)) {
            if (BuildConfig.DEBUG) {
                Log.d("UserSession","Applying default preferences for notification preferences");
            }
            PreferenceManager.setDefaultValues(fetLifeApplication, userPreferenceName, Context.MODE_PRIVATE, R.xml.notification_preferences, true);
            activePreferences.edit().putBoolean(USER_PREF_KEY_NOTIFICATION_PREFERNCES_APPLIED, true);
        }

        if (!activePreferences.getBoolean(USER_PREF_KEY_PROFILE_PREFERNCES_APPLIED, false)) {
            if (BuildConfig.DEBUG) {
                Log.d("UserSession","Applying default preferences for profile preferences");
            }
            PreferenceManager.setDefaultValues(fetLifeApplication, userPreferenceName, Context.MODE_PRIVATE, R.xml.profile_preferences, true);
            activePreferences.edit().putBoolean(USER_PREF_KEY_PROFILE_PREFERNCES_APPLIED, true);
        }

        if (!activePreferences.getBoolean(USER_PREF_KEY_FEED_PREFERENCES_APPLIED, false)) {
            if (BuildConfig.DEBUG) {
                Log.d("UserSession","Applying default preferences for feed preferences");
            }
            PreferenceManager.setDefaultValues(fetLifeApplication, userPreferenceName, Context.MODE_PRIVATE, R.xml.feed_preferences, true);
            activePreferences.edit().putBoolean(USER_PREF_KEY_FEED_PREFERENCES_APPLIED, true);
        }

    }

    private String getUserPreferenceName(String userId) {
        return "fetlife" + "_" + SecurityUtil.hash_sha256(userId) + ".pref";
    }

    private void clearUserPreferences() {
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Clearing user preferences");
        }
        //Note: Preferences are already saved
        activePreferences = null;
    }

    private void deletedUserPreference(String userId) {
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Deleting user preferences for user " + userId);
        }

        String userPreferenceName = getUserPreferenceName(userId);
        SharedPreferences userPreferences = fetLifeApplication.getSharedPreferences(userPreferenceName, Context.MODE_PRIVATE);
        if (BuildConfig.DEBUG) {
            if (userPreferences == null) {
                Log.d("UserSession","User preferences not found");
            } else {
                Log.d("UserSession","User preferences contain " + userPreferences.getAll().size() + " preferences");
            }
        }
        userPreferences.edit().clear().apply();
    }


    public boolean keepUserSignedIn() {
        boolean askAlwaysForPassword = activePreferences.getBoolean(PreferenceKeys.PREF_KEY_PASSWORD_ALWAYS, true);
        return !askAlwaysForPassword;
    }

    private void setKeepUserSignedIn(boolean keepUserSignedIn) {
        boolean askAlwaysForPassword = !keepUserSignedIn;
        activePreferences.edit().putBoolean(PreferenceKeys.PREF_KEY_PASSWORD_ALWAYS, askAlwaysForPassword).apply();
    }

    //User Database managing calls

    private void startDb() {
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Starting Db");
        }
        FlowManager.init(new FlowConfig.Builder(fetLifeApplication).build());
    }


    private void stopDb() {
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Stopping Db");
        }
        fetLifeApplication.setDbPathContent(null);
        FlowManager.init(fetLifeApplication);
//        FlowManager.destroy();
    }

    private void loadUserDb(String userId) {
        fetLifeApplication.setDbPathContent(getUserDatabaseName(userId));
//
//        if (BuildConfig.DEBUG) {
//            Log.d("UserSession","Loading Db for " + userId);
//        }
//        File databaseFile = fetLifeApplication.getDatabasePath(getDefaultDatabaseName());
//        if (databaseFile == null || !databaseFile.exists()) {
//            if (BuildConfig.DEBUG) {
//                Log.e("UserSession","Default Db File was not found");
//            }
//            Crashlytics.logException(new Exception("Default Database File not found"));
//            return;
//        }
//        File userDatabaseFile = new File(fetLifeApplication.getFilesDir(),getUserDatabaseName(userId));
//        if (!userDatabaseFile.exists()) {
//            if (BuildConfig.DEBUG) {
//                Log.d("UserSession","User Db File does not exist; Clearing Default Db");
//            }
//            FileUtil.clearContent(databaseFile);
//            return;
//        } else {
//            if (BuildConfig.DEBUG) {
//                Log.d("UserSession","User Db File found; Copying content to Default Db");
//            }
//            FileUtil.copyFileContent(userDatabaseFile,databaseFile);
//        }
    }


    public void resetDb() {
        startDb();
        FlowManager.reset();
        fetLifeApplication.deleteAllDatabase();
        FlowManager.destroy();
        logOutUser();
    }

    private void deleteCurrentUserDb() {
        fetLifeApplication.deleteDatabase();
        FlowManager.reset();
        FlowManager.destroy();
//        if (BuildConfig.DEBUG) {
//            Log.d("UserSession","Deleting user Db");
//        }
//        File userDatabaseFile = new File(fetLifeApplication.getFilesDir(),getUserDatabaseName(userId));
//        if (userDatabaseFile.exists()) {
//            if (BuildConfig.DEBUG) {
//                Log.d("UserSession","User Db was found");
//            }
//            userDatabaseFile.delete();
//        } else {
//            if (BuildConfig.DEBUG) {
//                Log.d("UserSession","User Db does not exist");
//            }
//        }
    }

    private Member loadUserRecord(String userId) {
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Loading user record for user " + userId);
        }
        Member user = Member.loadMember(userId);
        if (user == null) {
            if (BuildConfig.DEBUG) {
                Log.e("UserSession","User record could not be found");
            }
            Crashlytics.logException(new Exception("User record could not be found"));
        }
        return user;
    }

    private void updateUserRecord(Member userRecord) {
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Updating user record for user " + userRecord.getId());
        }
        userRecord.mergeSave();
        currentUser = userRecord;
    }

    private static String getUserDatabaseName(String userId) {
        return FetLifeDatabase.NAME + "_" + SecurityUtil.hash_sha256(userId) + ".db";
    }

    //*** User Session Meta Info managing calls

    private static final String SEPARATOR_SESSION_META_INFO_USER = "\n";

    private boolean isUserLoggedIn(){
        SharedPreferences mainPreferences = PreferenceManager.getDefaultSharedPreferences(fetLifeApplication);
        return mainPreferences.getBoolean(PreferenceKeys.MAIN_PREF_KEY_USER_SESSION_STATE,false);
    }

    private void setUserLoggedIn(boolean loggedIn) {
        SharedPreferences mainPreferences = PreferenceManager.getDefaultSharedPreferences(fetLifeApplication);
        mainPreferences.edit().putBoolean(PreferenceKeys.MAIN_PREF_KEY_USER_SESSION_STATE,loggedIn).commit();
    }

    private String getLastLoggedInUserId() {
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Retrieving User Session Meta Info");
        }
        SharedPreferences mainPreferences = PreferenceManager.getDefaultSharedPreferences(fetLifeApplication);
        String sessionMetaInfo = mainPreferences.getString(PreferenceKeys.MAIN_PREF_KEY_USER_SESSION_META_INFO,null);
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Session Meta Info: " + sessionMetaInfo);
        }
        String[] loggedInUsers = sessionMetaInfo != null ? sessionMetaInfo.split(SEPARATOR_SESSION_META_INFO_USER) : new String[0];
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","User Session Meta Info Session History length: " + loggedInUsers.length);
        }
        if (loggedInUsers.length == 0) {
            return null;
        }
        String lastLoggedInUser = loggedInUsers[0];
        if (lastLoggedInUser == null || lastLoggedInUser.trim().length() == 0) {
            if (BuildConfig.DEBUG) {
                Log.d("UserSession","No last user found");
            }
            return null;
        }
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Last logged in User: " + lastLoggedInUser);
        }
        return lastLoggedInUser;
    }

    private void updateUserMetaInfo(String userId) {
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Updating User Session Meta Info for user "+ userId);
        }
        setUserInMetaInfo(userId,false);
    }

    private void deleteUserMetaInfo(String userId) {
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","Updating User Session Meta Info for user "+ userId);
        }
        setUserInMetaInfo(userId,true);
    }

    private void setUserInMetaInfo(String userId, boolean removeOnly) {
        SharedPreferences mainPreferences = PreferenceManager.getDefaultSharedPreferences(fetLifeApplication);
        String sessionMetaInfo = mainPreferences.getString(PreferenceKeys.MAIN_PREF_KEY_USER_SESSION_META_INFO,null);
        String[] loggedInUsers = sessionMetaInfo != null ? sessionMetaInfo.split(SEPARATOR_SESSION_META_INFO_USER) : new String[0];
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","User Session Meta Info Session History length: " + loggedInUsers.length);
        }
        List<String> sessionHistory = new ArrayList<>(Arrays.asList(loggedInUsers));
        for (int i = 0; i < sessionHistory.size(); i++) {
            if (userId.equals(sessionHistory.get(i))) {
                sessionHistory.remove(i);
                if (BuildConfig.DEBUG) {
                    Log.d("UserSession","User found in session history at place : " + i);
                }
                break;
            }
        }
        if (!removeOnly) {
            sessionHistory.add(0,userId);
        }
        String newSessionMeta = StringUtil.toString(sessionHistory,SEPARATOR_SESSION_META_INFO_USER);
        if (BuildConfig.DEBUG) {
            Log.d("UserSession","New Session Meta Info: " + newSessionMeta);
        }
        mainPreferences.edit().putString(PreferenceKeys.MAIN_PREF_KEY_USER_SESSION_META_INFO, newSessionMeta).commit();
    }

    //*** Push message registration managing calls

    private static final String CONSTANT_ONESIGNAL_TAG_VERSION = "version";
    private static final String CONSTANT_ONESIGNAL_TAG_NICKNAME = "nickname";
    private static final String CONSTANT_ONESIGNAL_TAG_MEMBER_TOKEN = "member_token";

    private void registerToPushMessages(Member user) {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put(CONSTANT_ONESIGNAL_TAG_VERSION,1);
            jsonObject.put(CONSTANT_ONESIGNAL_TAG_NICKNAME, user.getNickname());
            jsonObject.put(CONSTANT_ONESIGNAL_TAG_MEMBER_TOKEN, user.getNotificationToken());
            OneSignal.sendTags(jsonObject);
            OneSignal.setSubscription(true);
        } catch (JSONException e) {
            //TODO: think about possible error handling
        }
    }

    private void unregisterFromPushMessages(Member user) {
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put(CONSTANT_ONESIGNAL_TAG_VERSION, 1);
            jsonObject.put(CONSTANT_ONESIGNAL_TAG_NICKNAME, user.getNickname());
            jsonObject.put(CONSTANT_ONESIGNAL_TAG_MEMBER_TOKEN, "");
            OneSignal.sendTags(jsonObject);

            String[] tags = new String[]{
                    CONSTANT_ONESIGNAL_TAG_VERSION,
                    CONSTANT_ONESIGNAL_TAG_NICKNAME,
                    CONSTANT_ONESIGNAL_TAG_MEMBER_TOKEN
            };
            OneSignal.deleteTags(Arrays.asList(tags));
        } catch (JSONException e) {
            //TODO: think about possible error handling
        }
    }

}
