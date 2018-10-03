package io.flutter.embedding.engine.dart;

import android.content.res.AssetManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.nio.ByteBuffer;

import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.view.FlutterCallbackInformation;

/**
 * Configures, bootstraps, and starts executing Dart code.
 *
 * To specify a top-level Dart function to execute, use a {@link DartEntrypoint} to tell
 * {@link DartExecutor} where to find the Dart code to execute, and which Dart function to use as
 * the entrypoint. To execute the entrypoint, pass the {@link DartEntrypoint} to
 * {@link #executeDartEntrypoint(DartEntrypoint)}.
 *
 * To specify a Dart callback to execute, use a {@link DartCallback}. A given Dart callback must
 * be registered with the Dart VM to be invoked by a {@link DartExecutor}. To execute the callback,
 * pass the {@link DartCallback} to {@link #executeDartCallback(DartCallback)}.
 * TODO(mattcarroll): add a reference to docs about background/plugin execution
 *
 * Once started, a {@link DartExecutor} cannot be stopped. The associated Dart code will execute
 * until it completes, or until the {@link io.flutter.embedding.engine.FlutterEngine} that owns
 * this {@link DartExecutor} is destroyed.
 */
public class DartExecutor implements BinaryMessenger {
  private static final String TAG = "DartExecutor";

  private final FlutterJNI flutterJNI;
  private final long nativeObjectReference;
  private final DartMessenger messenger;
  private boolean isApplicationRunning = false;

  public DartExecutor(@NonNull FlutterJNI flutterJNI, long nativeObjectReference) {
    this.flutterJNI = flutterJNI;
    this.nativeObjectReference = nativeObjectReference;
    this.messenger = new DartMessenger(flutterJNI, nativeObjectReference);
  }

  /**
   * Invoked when the {@link io.flutter.embedding.engine.FlutterEngine} that owns this
   * {@link DartExecutor} attaches to JNI.
   *
   * When attached to JNI, this {@link DartExecutor} begins handling 2-way communication to/from
   * the Dart execution context. This communication is facilitate via 2 APIs:
   *  - {@link BinaryMessenger}, which sends messages to Dart
   *  - {@link PlatformMessageHandler}, which receives messages from Dart
   */
  public void onAttachedToJNI() {
    flutterJNI.setPlatformMessageHandler(messenger);
    messenger.onAttachedToJni();
  }

  /**
   * Invoked when the {@link io.flutter.embedding.engine.FlutterEngine} that owns this
   * {@link DartExecutor} detached from JNI.
   *
   * When detached from JNI, this {@link DartExecutor} stops handling 2-way communication to/from
   * the Dart execution context.
   */
  public void onDetachedFromJNI() {
    flutterJNI.setPlatformMessageHandler(null);
    messenger.onDetachedFromJni();
  }

  /**
   * Is this {@link DartExecutor} currently executing Dart code?
   * @return true if Dart code is being executed, false otherwise
   */
  public boolean isExecutingDart() {
    return isApplicationRunning;
  }

  /**
   * Starts executing Dart code based on the given {@code dartEntrypoint}.
   *
   * See {@link DartEntrypoint} for configuration options.
   *
   * @param dartEntrypoint specifies which Dart function to run, and where to find it
   */
  public void executeDartEntrypoint(DartEntrypoint dartEntrypoint) {
    if (isApplicationRunning) {
      Log.w(TAG, "Attempted to run a DartExecutor that is already running.");
      return;
    }

    flutterJNI.nativeRunBundleAndSnapshotFromLibrary(
        nativeObjectReference,
        dartEntrypoint.pathToBundleWithDartEntrypoint,
        dartEntrypoint.pathToFallbackBundle,
        dartEntrypoint.dartEntrypointFunctionName,
        null,
        dartEntrypoint.androidAssetManager
    );

    isApplicationRunning = true;
  }

  /**
   * Starts executing Dart code based on the given {@code dartCallback}.
   *
   * See {@link DartCallback} for configuration options.
   *
   * @param dartCallback specifies which Dart callback to run, and where to find it
   */
  public void executeDartCallback(DartCallback dartCallback) {
    if (isApplicationRunning) {
      Log.w(TAG, "Attempted to run a DartExecutor that is already running.");
      return;
    }

    flutterJNI.nativeRunBundleAndSnapshotFromLibrary(
        nativeObjectReference,
        dartCallback.pathToBundleWithDartEntrypoint,
        dartCallback.pathToFallbackBundle,
        dartCallback.callbackHandle.callbackName,
        dartCallback.callbackHandle.callbackLibraryPath,
        dartCallback.androidAssetManager
    );

    isApplicationRunning = true;
  }

  //------ START BinaryMessenger -----
  /**
   * Sends the given {@code message} from Android to Dart over the given {@code channel}.
   *
   * @param channel the name of the logical channel used for the message.
   * @param message the message payload, a direct-allocated {@link ByteBuffer} with the message bytes
   */
  @Override
  public void send(String channel, ByteBuffer message) {
    messenger.send(channel, message, null);
  }

  /**
   * Sends the given {@code messages} from Android to Dart over the given {@code channel} and
   * then has the provided {@code callback} invoked when the Dart side responds.
   *
   * @param channel the name of the logical channel used for the message.
   * @param message the message payload, a direct-allocated {@link ByteBuffer} with the message bytes
   * between position zero and current position, or null.
   * @param callback a callback invoked when the Dart application responds to the message
   */
  @Override
  public void send(String channel, ByteBuffer message, BinaryMessenger.BinaryReply callback) {
    messenger.send(channel, message, callback);
  }

  /**
   * Sets the given {@link io.flutter.plugin.common.BinaryMessenger.BinaryMessageHandler} as the
   * singular handler for all incoming messages received from the Dart side of this Dart execution
   * context.
   *
   * @param channel the name of the channel.
   * @param handler a {@link BinaryMessageHandler} to be invoked on incoming messages, or null.
   */
  @Override
  public void setMessageHandler(String channel, BinaryMessenger.BinaryMessageHandler handler) {
    messenger.setMessageHandler(channel, handler);
  }
  //------ END BinaryMessenger -----

  // TODO(mattcarroll): Implement observatory lookup for "flutter attach"
  public String getObservatoryUrl() {
    return flutterJNI.nativeGetObservatoryUri();
  }

  /**
   * Configuration options that specify which Dart entrypoint function is executed and where
   * to find that entrypoint and other assets required for Dart execution.
   */
  public static class DartEntrypoint {
    public final AssetManager androidAssetManager;
    public final String pathToBundleWithDartEntrypoint;
    public final String pathToFallbackBundle;
    public final String dartEntrypointFunctionName;

    public DartEntrypoint(
        @NonNull AssetManager androidAssetManager,
        @NonNull String pathToBundleWithDartEntrypoint,
        @NonNull String dartEntrypointFunctionName
    ) {
      this(
          androidAssetManager,
          pathToBundleWithDartEntrypoint,
          null,
          dartEntrypointFunctionName
      );
    }

    public DartEntrypoint(
        @NonNull AssetManager androidAssetManager,
        @NonNull String pathToBundleWithDartEntrypoint,
        @Nullable String pathToFallbackBundle,
        @NonNull String dartEntrypointFunctionName
    ) {
      this.androidAssetManager = androidAssetManager;
      this.pathToBundleWithDartEntrypoint = pathToBundleWithDartEntrypoint;
      this.pathToFallbackBundle = pathToFallbackBundle;
      this.dartEntrypointFunctionName = dartEntrypointFunctionName;
    }
  }

  /**
   * Configuration options that specify which Dart callback function is executed and where
   * to find that callback and other assets required for Dart execution.
   */
  public static class DartCallback {
    public final AssetManager androidAssetManager;
    public final String pathToBundleWithDartEntrypoint;
    public final String pathToFallbackBundle;
    public final FlutterCallbackInformation callbackHandle;

    public DartCallback(
        @NonNull AssetManager androidAssetManager,
        @NonNull String pathToBundleWithDartEntrypoint,
        @NonNull FlutterCallbackInformation callbackHandle
    ) {
      this(
          androidAssetManager,
          pathToBundleWithDartEntrypoint,
          null,
          callbackHandle
      );
    }

    public DartCallback(
        @NonNull AssetManager androidAssetManager,
        @NonNull String pathToBundleWithDartEntrypoint,
        @Nullable String pathToFallbackBundle,
        @NonNull FlutterCallbackInformation callbackHandle
    ) {
      this.androidAssetManager = androidAssetManager;
      this.pathToBundleWithDartEntrypoint = pathToBundleWithDartEntrypoint;
      this.pathToFallbackBundle = pathToFallbackBundle;
      this.callbackHandle = callbackHandle;
    }
  }
}
