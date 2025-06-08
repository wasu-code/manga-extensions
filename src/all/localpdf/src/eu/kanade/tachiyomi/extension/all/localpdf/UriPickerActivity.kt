package eu.kanade.tachiyomi.extension.all.localpdf

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class UriPickerActivity : Activity() {
    companion object {
        private const val REQUEST_CODE_OPEN_DOCUMENT_TREE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launch SAF picker
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            }
        startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT_TREE)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_OPEN_DOCUMENT_TREE && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                val shareIntent =
                    Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, uri.toString())
                    }
                startActivity(Intent.createChooser(shareIntent, "Share URI via"))
                Toast.makeText(this, "Copy this URI", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "No URI", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }
}
