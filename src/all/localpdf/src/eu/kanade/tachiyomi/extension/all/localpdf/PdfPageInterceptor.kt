package eu.kanade.tachiyomi.extension.all.localpdf

import android.app.Application
import android.content.res.Resources.NotFoundException
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import com.hippo.unifile.UniFile
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream

class PdfPageInterceptor(
    private val context: Application,
    private val inputDir: UniFile?,
    private val scale: Int,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        val segments = url.pathSegments

        val mangaName = segments[0]
        val chapterFileName = segments[1]
        val pageStr = segments[2]

        val pageIndex = pageStr.removePrefix("page").toIntOrNull() ?: throw IllegalArgumentException("Invalid page number")

        val pdfFile = inputDir?.findFile(mangaName)?.findFile(chapterFileName) ?: throw NotFoundException("PDF file not found")

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
