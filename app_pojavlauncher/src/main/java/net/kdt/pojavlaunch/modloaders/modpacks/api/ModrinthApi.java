package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModrinthIndex;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;

public class ModrinthApi implements ModpackApi {
    private ApiHandler mApiHandler;
    public ModrinthApi(){
        mApiHandler = new ApiHandler("https://api.modrinth.com/v2");
    }

    @Override
    public ModItem[] searchMod(boolean searchModpack, String minecraftVersion, String name) {
        HashMap<String, Object> params = new HashMap<>();

        params.put("facets", String.format("[[\"project_type:%s\"],[\"versions:%s\"]]",
                searchModpack ? "modpack" : "mod",
                minecraftVersion
        ));
        params.put("query", name);

        JsonObject response = mApiHandler.get("search", params, JsonObject.class);
        JsonArray responseHits = response.getAsJsonArray("hits");

        ModItem[] items = new ModItem[responseHits.size()];
        for(int i=0; i<responseHits.size(); ++i){
            JsonObject hit = responseHits.get(i).getAsJsonObject();
            items[i] = new ModItem(
                    Constants.SOURCE_MODRINTH,
                    hit.get("project_type").getAsString().equals("modpack"),
                    hit.get("project_id").getAsString(),
                    hit.get("title").getAsString(),
                    hit.get("description").getAsString(),
                    hit.get("icon_url").getAsString()
            );
        }

        return items;
    }

    @Override
    public ModDetail getModDetails(ModItem item, String targetMcVersion) {
        HashMap<String, Object> queryParams  = new HashMap<>();
        queryParams.put("game_versions", String.format("[\"%s\"]", targetMcVersion));
        JsonArray response = mApiHandler.get(String.format("project/%s/version", item.id), queryParams, JsonArray.class);
        System.out.println(response.toString());
        String[] names = new String[response.size()];
        String[] urls = new String[response.size()];

        for (int i=0; i<response.size(); ++i) {
            JsonObject version = response.get(i).getAsJsonObject();
            names[i] = version.get("name").getAsString();
            urls[i] = version.get("files").getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString();
        }

        return new ModDetail(item, names, urls);
    }

    @Override
    public void installMod(ModDetail modDetail, String versionUrl, String mcVersion) {
        //TODO considering only modpacks for now
        String modpackName = modDetail.title.toLowerCase(Locale.ROOT).trim().replace(" ", "_" );

        // Build a new minecraft instance, folder first
        File instanceFolder = new File(Tools.DIR_CACHE, modpackName);
        instanceFolder.mkdirs();

        // Get the mrpack
        File modpackFile = new File(Tools.DIR_CACHE, modpackName + ".mrpack");
        try {
            DownloadUtils.downloadFile(versionUrl, modpackFile);

            FileUtils.uncompressZip(modpackFile, instanceFolder);

            // Get the index
            ModrinthIndex index = Tools.GLOBAL_GSON.fromJson(Tools.read(instanceFolder.getAbsolutePath() + "/modrinth.index.json"), ModrinthIndex.class);
            System.out.println(index);
            // Download mods
            for (ModrinthIndex.ModrinthIndexFile file : index.files){
                File destFile = new File(instanceFolder, file.path);
                destFile.getParentFile().mkdirs();
                DownloadUtils.downloadFile(file.downloads[0], destFile);
            }

            // Apply the overrides
            for(String overrideName : new String[]{"overrides", "client-overrides"}) {
                File overrideFolder = new File(instanceFolder, overrideName);
                if(!overrideFolder.exists() || !overrideFolder.isDirectory()){
                    continue;
                }
                for(File file : overrideFolder.listFiles()){
                    // TODO what if overrides + client-overrides have collisions ?
                    org.apache.commons.io.FileUtils.moveToDirectory(file, instanceFolder, true);
                }
                overrideFolder.delete();
            }
            // Remove server override as it is pointless
            org.apache.commons.io.FileUtils.deleteDirectory(new File(instanceFolder, "server-overrides"));

            // Move the instance folder
            org.apache.commons.io.FileUtils.moveDirectoryToDirectory(instanceFolder, new File(Tools.DIR_GAME_HOME, "custom_instances"), true);

        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            modpackFile.delete();
            try {
                org.apache.commons.io.FileUtils.deleteDirectory(instanceFolder);
            } catch (IOException e) {
                Log.e(ModrinthApi.class.toString(), "Failed to cleanup cache instance folder, if any");
            }
        }

        // Create the instance
        MinecraftProfile profile = new MinecraftProfile();
        profile.gameDir = "./custom_instances/" + modpackName;
        profile.name = modpackName;
        //FIXME add the proper version !
        profile.lastVersionId = mcVersion;

        LauncherProfiles.mainProfileJson.profiles.put(modpackName, profile);
        LauncherProfiles.update();
    }

}