package hk.uwu.reareye.widgetapi;

import android.os.Bundle;

interface IRearAppApiService {
    Bundle getCatalog();
    Bundle registerAppCard(String title, String componentBusiness);
    Bundle renameAppCard(String appId, String title);
    Bundle renderAppCardPreview(String appId);
    Bundle deleteAppCard(String appId);
    Bundle reorderAppCards(in Bundle request);
}
