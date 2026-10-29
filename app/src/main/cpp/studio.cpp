#include <jni.h>
#include <android/bitmap.h>
#include <webp/mux.h>
#include <webp/demux.h>
#include <webp/encode.h>
#include <fstream>
#include <vector>
#include <memory>
#include <cstring>
#include <new>

static void fail(JNIEnv* e, const char* s) { e->ThrowNew(e->FindClass("java/lang/IllegalArgumentException"), s); }
static std::vector<uint8_t> read(JNIEnv* e, jstring path) {
    const char* p = e->GetStringUTFChars(path, nullptr);
    if (!p) return {};
    std::ifstream f(p, std::ios::binary | std::ios::ate); e->ReleaseStringUTFChars(path,p);
    if (!f) { fail(e,"Cannot read WebP file"); return {}; }
    auto n = f.tellg(); if (n <= 0 || n > 100*1024*1024) { fail(e,"WebP input exceeds 100 MB or is empty"); return {}; }
    std::vector<uint8_t> v(static_cast<size_t>(n)); f.seekg(0); f.read(reinterpret_cast<char*>(v.data()),n); if (!f) { fail(e,"Incomplete WebP read"); return {}; } return v;
}
extern "C" JNIEXPORT jintArray JNICALL Java_com_stickerstudio_NativeWebp_info(JNIEnv* e,jobject,jstring path) try {
    auto bytes=read(e,path); if(e->ExceptionCheck()) return nullptr;
    WebPData data{bytes.data(),bytes.size()}; auto* d=WebPDemux(&data);
    if(!d) {fail(e,"Malformed WebP"); return nullptr;}
    int64_t duration=0;int minDelay=10000; WebPIterator it;
    if(WebPDemuxGetFrame(d,1,&it)) { do { duration+=it.duration; minDelay=std::min(minDelay,it.duration); } while(WebPDemuxNextFrame(&it)); WebPDemuxReleaseIterator(&it); }
    if(duration>INT32_MAX){WebPDemuxDelete(d);fail(e,"WebP duration is too long");return nullptr;}
    jint a[]={static_cast<int>(WebPDemuxGetI(d,WEBP_FF_CANVAS_WIDTH)),static_cast<int>(WebPDemuxGetI(d,WEBP_FF_CANVAS_HEIGHT)),static_cast<int>(duration),static_cast<int>(WebPDemuxGetI(d,WEBP_FF_FORMAT_FLAGS)&ANIMATION_FLAG),minDelay};
    WebPDemuxDelete(d); auto out=e->NewIntArray(5); e->SetIntArrayRegion(out,0,5,a); return out;
} catch (const std::bad_alloc&) { fail(e,"Not enough memory for this media"); return 0; }
struct Decoder { std::vector<uint8_t> bytes; WebPAnimDecoder* decoder=nullptr; ~Decoder(){if(decoder)WebPAnimDecoderDelete(decoder);} };
// Mux two identical frames without re-encoding or animation-frame deduplication.
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_stickerstudio_NativeWebp_wrapStill(JNIEnv* e,jobject,jstring path) try {
    auto bytes=read(e,path); if(e->ExceptionCheck()) return nullptr;
    std::unique_ptr<WebPMux, decltype(&WebPMuxDelete)> mux(WebPMuxNew(), WebPMuxDelete);
    if(!mux){fail(e,"Cannot create sticker wrapper");return nullptr;}
    WebPMuxAnimParams params{0,0};
    WebPMuxFrameInfo frame{};
    frame.bitstream={bytes.data(),bytes.size()};
    frame.id=WEBP_CHUNK_ANMF; frame.duration=500;
    frame.dispose_method=WEBP_MUX_DISPOSE_NONE; frame.blend_method=WEBP_MUX_NO_BLEND;
    WebPData data; WebPDataInit(&data);
    if(WebPMuxSetAnimationParams(mux.get(),&params)!=WEBP_MUX_OK ||
       WebPMuxPushFrame(mux.get(),&frame,1)!=WEBP_MUX_OK ||
       WebPMuxPushFrame(mux.get(),&frame,1)!=WEBP_MUX_OK ||
       WebPMuxAssemble(mux.get(),&data)!=WEBP_MUX_OK){
        WebPDataClear(&data); fail(e,"Cannot prepare still sticker for mixed pack");return nullptr;
    }
    auto out=e->NewByteArray(static_cast<jsize>(data.size));
    if(out)e->SetByteArrayRegion(out,0,static_cast<jsize>(data.size),reinterpret_cast<const jbyte*>(data.bytes));
    WebPDataClear(&data); return out;
} catch (const std::bad_alloc&) { fail(e,"Not enough memory for this media"); return 0; }
extern "C" JNIEXPORT jlong JNICALL Java_com_stickerstudio_NativeWebp_openDecoder(JNIEnv* e,jobject,jstring path) try {
    auto d=std::make_unique<Decoder>(); d->bytes=read(e,path); if(e->ExceptionCheck())return 0;
    WebPData data{d->bytes.data(),d->bytes.size()}; WebPAnimDecoderOptions o; WebPAnimDecoderOptionsInit(&o); o.color_mode=MODE_rgbA;
    d->decoder=WebPAnimDecoderNew(&data,&o); if(!d->decoder){fail(e,"Cannot decode animation");return 0;}
    WebPAnimInfo info; WebPAnimDecoderGetInfo(d->decoder,&info);
    if(info.canvas_width>4096 || info.canvas_height>4096){fail(e,"Animation dimensions exceed 4096 pixels");return 0;}
    return reinterpret_cast<jlong>(d.release());
} catch (const std::bad_alloc&) { fail(e,"Not enough memory for this media"); return 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_stickerstudio_NativeWebp_nextFrame(JNIEnv* e,jobject,jlong h,jobject bitmap) try {
    auto* d=reinterpret_cast<Decoder*>(h); if(!d||!WebPAnimDecoderHasMoreFrames(d->decoder))return -1;
    uint8_t* buf; int timestamp; if(!WebPAnimDecoderGetNext(d->decoder,&buf,&timestamp)){fail(e,"Damaged animation frame");return -1;}
    WebPAnimInfo info; WebPAnimDecoderGetInfo(d->decoder,&info); AndroidBitmapInfo bi; void* dst;
    if(AndroidBitmap_getInfo(e,bitmap,&bi)!=0 || bi.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || bi.width!=info.canvas_width || bi.height!=info.canvas_height){fail(e,"Frame bitmap mismatch");return -1;}
    if(AndroidBitmap_lockPixels(e,bitmap,&dst)!=0){fail(e,"Cannot lock frame");return -1;}
    for(uint32_t y=0;y<bi.height;y++)memcpy(static_cast<uint8_t*>(dst)+y*bi.stride,buf+y*bi.width*4,bi.width*4);
    AndroidBitmap_unlockPixels(e,bitmap); return timestamp;
} catch (const std::bad_alloc&) { fail(e,"Not enough memory for this media"); return 0; }
extern "C" JNIEXPORT void JNICALL Java_com_stickerstudio_NativeWebp_closeDecoder(JNIEnv*,jobject,jlong h){delete reinterpret_cast<Decoder*>(h);}
struct Encoder {
    WebPAnimEncoder* encoder=nullptr;
    WebPConfig config;
    // One bounded scratch buffer per conversion, not a new MiB every frame.
    std::vector<uint8_t> rgba = std::vector<uint8_t>(512*512*4);
    ~Encoder(){WebPAnimEncoderDelete(encoder);}
};
extern "C" JNIEXPORT jlong JNICALL Java_com_stickerstudio_NativeWebp_openEncoder(JNIEnv* e,jobject,jfloat quality) try {
    WebPAnimEncoderOptions o; WebPAnimEncoderOptionsInit(&o); o.anim_params.loop_count=0;
    // Default disposal heuristics avoid encoding both candidates for each frame.
    o.minimize_size=0;
    auto* enc=WebPAnimEncoderNew(512,512,&o); if(!enc){fail(e,"Cannot create animation encoder");return 0;}
    std::unique_ptr<WebPAnimEncoder, decltype(&WebPAnimEncoderDelete)> guard(enc, WebPAnimEncoderDelete);
    auto out=std::make_unique<Encoder>(); out->encoder=guard.release(); WebPConfigInit(&out->config); out->config.quality=quality; out->config.method=3;
    return reinterpret_cast<jlong>(out.release());
} catch (const std::bad_alloc&) { fail(e,"Not enough memory for this media"); return 0; }
extern "C" JNIEXPORT void JNICALL Java_com_stickerstudio_NativeWebp_addFrame(JNIEnv* e,jobject,jlong h,jobject bitmap,jint time) try {
    auto* enc=reinterpret_cast<Encoder*>(h); AndroidBitmapInfo bi; void* src;
    if(!enc || AndroidBitmap_getInfo(e,bitmap,&bi)!=0 || bi.width!=512 || bi.height!=512 || bi.format!=ANDROID_BITMAP_FORMAT_RGBA_8888){fail(e,"Invalid encoder frame");return;}
    if(AndroidBitmap_lockPixels(e,bitmap,&src)!=0){fail(e,"Cannot lock bitmap");return;}
    auto& rgba=enc->rgba;
    for(int y=0;y<512;y++) for(int x=0;x<512;x++){
        auto* p=static_cast<uint8_t*>(src)+y*bi.stride+x*4; auto* q=rgba.data()+(y*512+x)*4; int a=p[3]; q[3]=a;
        if(a==255) { q[0]=p[0];q[1]=p[1];q[2]=p[2]; }
        else for(int c=0;c<3;c++)q[c]=a?std::min(255,(p[c]*255+a/2)/a):0;
    }
    AndroidBitmap_unlockPixels(e,bitmap); WebPPicture pic; WebPPictureInit(&pic); pic.width=512;pic.height=512;pic.use_argb=1;
    bool ok=WebPPictureImportRGBA(&pic,rgba.data(),512*4) && WebPAnimEncoderAdd(enc->encoder,&pic,time,&enc->config);
    WebPPictureFree(&pic); if(!ok)fail(e,"Animation frame encoding failed");
} catch (const std::bad_alloc&) { fail(e,"Not enough memory for this media"); return; }
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_stickerstudio_NativeWebp_finish(JNIEnv* e,jobject,jlong h,jint time) try {
    auto* enc=reinterpret_cast<Encoder*>(h); WebPData data; WebPDataInit(&data);
    if(!enc || !WebPAnimEncoderAdd(enc->encoder,nullptr,time,nullptr) || !WebPAnimEncoderAssemble(enc->encoder,&data)){fail(e,"Animation assembly failed");return nullptr;}
    auto out=e->NewByteArray(static_cast<jsize>(data.size)); if(out)e->SetByteArrayRegion(out,0,static_cast<jsize>(data.size),reinterpret_cast<const jbyte*>(data.bytes)); WebPDataClear(&data); return out;
} catch (const std::bad_alloc&) { fail(e,"Not enough memory for this media"); return 0; }
extern "C" JNIEXPORT void JNICALL Java_com_stickerstudio_NativeWebp_closeEncoder(JNIEnv*,jobject,jlong h){delete reinterpret_cast<Encoder*>(h);}
