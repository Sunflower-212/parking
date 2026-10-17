package org.zjmproject.service;

import org.json.JSONException;

import java.io.IOException;

public interface LicensePlateRecognitionService {
    String getPlate(String path) throws IOException, JSONException;
}
