package abi46_0_0.host.exp.exponent.modules.api.components.maps;

import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import abi46_0_0.com.facebook.react.bridge.ReactApplicationContext;
import abi46_0_0.com.facebook.react.bridge.ReadableArray;
import abi46_0_0.com.facebook.react.bridge.ReadableMap;
import abi46_0_0.com.facebook.react.uimanager.ThemedReactContext;
import abi46_0_0.com.facebook.react.uimanager.ViewGroupManager;
import abi46_0_0.com.facebook.react.uimanager.annotations.ReactProp;
import com.google.android.gms.maps.model.LatLng;

import java.util.List;
import java.util.ArrayList;

public class AirMapGradientPolylineManager extends ViewGroupManager<AirMapGradientPolyline> {
  private final DisplayMetrics metrics;

  public AirMapGradientPolylineManager(ReactApplicationContext reactContext) {
    super();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      metrics = new DisplayMetrics();
      ((WindowManager) reactContext.getSystemService(Context.WINDOW_SERVICE))
          .getDefaultDisplay()
          .getRealMetrics(metrics);
    } else {
      metrics = reactContext.getResources().getDisplayMetrics();
    }
  }

  @Override
  public String getName() {
    return "AIRMapGradientPolyline";
  }

  @Override
  public AirMapGradientPolyline createViewInstance(ThemedReactContext context) {
    return new AirMapGradientPolyline(context);
  }

  @ReactProp(name = "coordinates")
  public void setCoordinates(AirMapGradientPolyline view, ReadableArray coordinates) {
    List<LatLng> p = new ArrayList<LatLng>();
    for (int i = 0; i < coordinates.size(); i++) {
      ReadableMap point = coordinates.getMap(i);
      LatLng latLng = new LatLng(point.getDouble("latitude"), point.getDouble("longitude"));
      p.add(latLng);
    }
    view.setCoordinates(p);
  }

  @ReactProp(name = "strokeColors", customType = "ColorArray")
  public void setStrokeColors(AirMapGradientPolyline view, ReadableArray colors) {
    if (colors != null) {
      if (colors.size() == 0) {
        int[] colorValues = {0,0};
        view.setStrokeColors(colorValues);
      } else if (colors.size() == 1) {
        int[] colorValues = { colors.getInt(0), colors.getInt(0) };
        view.setStrokeColors(colorValues);
      } else {
        int[] colorValues = new int[colors.size()];
        for (int i = 0; i < colors.size(); i++) {
          colorValues[i] = colors.getInt(i);
        }
        view.setStrokeColors(colorValues);
      }
    } else {
      int[] colorValues = {0,0};
      view.setStrokeColors(colorValues);
    }
  }

  @ReactProp(name = "zIndex", defaultFloat = 1.0f)
  public void setZIndex(AirMapGradientPolyline view, float zIndex) {
    view.setZIndex(zIndex);
  }


  @ReactProp(name = "strokeWidth", defaultFloat = 1f)
  public void setStrokeWidth(AirMapGradientPolyline view, float widthInPoints) {
    float widthInScreenPx = metrics.density * widthInPoints; // done for parity with iOS
    view.setWidth(widthInScreenPx);
  }
}