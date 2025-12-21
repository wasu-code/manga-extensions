package kuchihige.utils

import android.app.Application
import android.content.res.Resources.NotFoundException
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.hippo.unifile.UniFile
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream

private const val HOST = "pdf"

class PdfPageInterceptor(
    private val scale: Int,
) : Interceptor {
    val context by lazy { Injekt.get<Application>() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != HOST) return chain.proceed(request)

        val uri = url.encodedPath.removePrefix("/")
        val pageIndex = url.queryParameter("page")?.toIntOrNull() ?: throw Exception("Missing page parameter")

        val pdfFile = UniFile.fromUri(context, Uri.parse(uri)) ?: throw NotFoundException("File not found: $uri")

        val descriptor = context.contentResolver.openFileDescriptor(pdfFile.uri, "r")
        val renderer = PdfRenderer(descriptor!!)

        if (pageIndex >= renderer.pageCount) {
            renderer.close()
            descriptor.close()
            throw IllegalArgumentException("You requested page index $pageIndex while PDF has only ${renderer.pageCount} pages")
        }

        val page = renderer.openPage(pageIndex)

        val width = page.width * scale
        val height = page.height * scale

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        renderer.close()
        descriptor.close()

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val imageBytes = stream.toByteArray()

        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(imageBytes.toResponseBody("image/png".toMediaType()))
            .build()
    }
}

object PdfPageInterceptorHelper {
    fun createUrl(uri: Uri, pageIndex: Int): String {
        return "http://$HOST/$uri?page=$pageIndex"
    }
}
