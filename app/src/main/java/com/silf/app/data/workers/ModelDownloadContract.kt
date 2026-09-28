package com.silf.app.data.workers

object ModelDownloadContract {
    const val UNIQUE_PREFIX = "model-download-"
    const val DOWNLOAD_TAG = "silf_model_download"

    const val KEY_MODEL_ID = "model_id"
    const val KEY_MODEL_URL = "model_url"
    const val KEY_MODEL_NAME = "model_name"
    const val KEY_EXPECTED_BYTES = "expected_bytes"
    const val KEY_SHA256 = "sha256"
    const val KEY_MAX_BYTES = "max_bytes"
    const val KEY_FORMAT = "format"

    const val OUTPUT_FILE_PATH = "file_path"
    const val OUTPUT_MODEL_ID = "model_id"
    const val OUTPUT_ERROR = "error"

    const val PROGRESS_PERCENT = "progress_percent"
    const val PROGRESS_BYTES = "progress_bytes"
    const val PROGRESS_TOTAL = "progress_total"

    const val CHANNEL_ID = "model_downloads"
    const val NOTIFICATION_ID_BASE = 1010
    const val PROGRESS_STEP = 2
}
