package com.cloudwebrtc.webrtc;

import org.webrtc.*;
import io.flutter.plugin.common.MethodChannel.Result;
import com.cloudwebrtc.webrtc.utils.ConstraintsMap;
import com.cloudwebrtc.webrtc.utils.EglUtils;
import com.cloudwebrtc.webrtc.video.LocalVideoTrack;
import android.content.Context;

public class ElFaheemCapturerBridge {
    public static void createFrameCapturerTrack(
            StateProvider stateProvider, Context appContext,
            MediaStream mediaStream, Result result) {

        ElFaheemFrameCapturer capturer = new ElFaheemFrameCapturer();
        PeerConnectionFactory pcFactory = stateProvider.getPeerConnectionFactory();
        VideoSource videoSource = pcFactory.createVideoSource(true);
        SurfaceTextureHelper helper = SurfaceTextureHelper.create(
                "elfaheem_frame_thread", EglUtils.getRootEglBaseContext());

        capturer.initialize(helper, appContext, videoSource.getCapturerObserver());
        capturer.startCapture(1280, 720, 15);

        String trackId = stateProvider.getNextTrackUUID();
        VideoTrack track = pcFactory.createVideoTrack(trackId, videoSource);
        mediaStream.addTrack(track);
        stateProvider.putLocalTrack(track.id(), new LocalVideoTrack(track));

        ConstraintsMap trackParams = new ConstraintsMap();
        trackParams.putBoolean("enabled", track.enabled());
        trackParams.putString("id", track.id());
        trackParams.putString("kind", "video");
        trackParams.putString("label", track.id());
        trackParams.putString("readyState", track.state().toString());
        trackParams.putBoolean("remote", false);

        ConstraintsMap streamResult = new ConstraintsMap();
        streamResult.putString("streamId", mediaStream.getId());
        streamResult.putArray("videoTracks",
                new com.cloudwebrtc.webrtc.utils.ConstraintsArray() {{ pushMap(trackParams); }}.toArrayList());
        streamResult.putArray("audioTracks",
                new com.cloudwebrtc.webrtc.utils.ConstraintsArray().toArrayList());

        stateProvider.putLocalStream(mediaStream.getId(), mediaStream);
        result.success(streamResult.toMap());
    }
}