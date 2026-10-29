package com.stickerstudio

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder

class StudioApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader() =
        ImageLoader.Builder(this).components { add(ImageDecoderDecoder.Factory()) }.build()
}
