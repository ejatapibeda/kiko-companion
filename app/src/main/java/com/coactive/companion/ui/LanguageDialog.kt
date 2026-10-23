package com.coactive.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coactive.companion.R
import com.coactive.companion.settings.AppLanguage

/** Dialog pilih bahasa saat pertama kali dibuka (judul dwibahasa supaya terbaca siapa pun). */
@Composable
fun LanguageDialog(onChoose: (AppLanguage) -> Unit) {
    AlertDialog(
        onDismissRequest = {}, // wajib memilih; bisa diubah lagi di Pengaturan
        title = { Text(stringResource(R.string.language_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.language_dialog_body))
                Button(onClick = { onChoose(AppLanguage.EN) }, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("lang_EN")) { Text(stringResource(R.string.lang_english)) }
                OutlinedButton(onClick = { onChoose(AppLanguage.ID) }, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("lang_ID")) { Text(stringResource(R.string.lang_indonesian)) }
            }
        },
        confirmButton = {},
    )
}
