package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared batch provider-assignment dialogs (confirm / presence progress / result).
 * Single source of wording for both surfaces: Local Accounts screen and Dashboard
 * (Subscribers tab). Extracted from LocalAccountsScreen.kt so the two entry points
 * cannot drift apart.
 */

@Composable
fun BatchProviderConfirmDialog(
    targetProvider: String,
    selectedCount: Int,
    isAr: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isAr) "تأكيد تعيين المزود بالدفعة" else "Confirm Batch Provider Assignment",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isAr) "عدد الحسابات المحددة: $selectedCount" else "Selected Accounts: $selectedCount",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isAr) "المزود المستهدف: $targetProvider" else "Target Provider: $targetProvider",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = if (isAr) "تغيير المزود يغيّر وجهة عمليات الإنترنت المستقبلية.\n" +
                        "لا يؤدي ذلك إلى نقل المشترك لدى المزود الخارجي.\n" +
                        "السجل المحلي يبقى دون تغيير.\n" +
                        "العمليات قيد التنفيذ تحتفظ بمزودها الأصلي."
                    else "Changing provider changes where future ISP operations are sent.\n" +
                        "This does not migrate the external subscriber.\n" +
                        "Local history remains unchanged.\n" +
                        "Pending operations keep their original provider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = if (isAr) "ملاحظة: سيتم تخطي أي حساب يحتوي على عمليات معلقة نشطة تلقائياً لأمان المعاملات."
                        else "Note: Accounts with active in-flight operations will be skipped automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Text(
                    text = if (isAr) "ملاحظة: لن يتم التبديل إلا للمشتركين المؤكدين الوجود لدى المزود المستهدف؛ سيتم تخطي الباقي."
                        else "Note: Only subscribers confirmed present on the target provider will be switched; the rest are skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(if (isAr) "تأكيد التعيين" else "Confirm")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(if (isAr) "إلغاء" else "Cancel")
            }
        }
    )
}

@Composable
fun BatchProviderPresenceProgressDialog(
    done: Int,
    total: Int,
    isAr: Boolean
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                text = if (isAr) "جارٍ التحقق من الوجود لدى المزود…" else "Checking provider presence…",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val progress = if (total <= 0) 1f else done.toFloat() / total.toFloat()
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    text = if (isAr) "$done من أصل $total مشترك" else "$done of $total subscribers checked",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
fun BatchProviderResultDialog(
    result: com.example.core.model.BatchProviderResult,
    isAr: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isAr) "اكتملت عملية المزود بالدفعة" else "Batch Provider Change Complete",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (isAr) "تم التحديث: ${result.updated} من أصل ${result.totalSelected}" else "Updated: ${result.updated} of ${result.totalSelected}",
                    fontWeight = FontWeight.SemiBold
                )
                if (result.alreadyTarget > 0) {
                    Text(
                        text = if (isAr) "بالفعل على المزود المستهدف: ${result.alreadyTarget}" else "Already on target provider: ${result.alreadyTarget}",
                        color = Color.Gray
                    )
                }
                if (result.skippedDueToPending > 0) {
                    Text(
                        text = if (isAr) "تم التخطي (عمليات قيد التنفيذ): ${result.skippedDueToPending}" else "Skipped (in-flight operations): ${result.skippedDueToPending}",
                        color = Color.Gray
                    )
                    result.skippedDueToPendingNames.forEach { name ->
                        Text(text = "• $name", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                if (result.notOnTargetNames.isNotEmpty()) {
                    Text(
                        text = if (isAr) "تم التخطي (غير مؤكد الوجود لدى المزود المستهدف): ${result.notOnTargetNames.size}" else "Skipped (not confirmed on target provider): ${result.notOnTargetNames.size}",
                        color = Color(0xFFC62828)
                    )
                    result.notOnTargetNames.forEach { name ->
                        Text(text = "• $name", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828))
                    }
                }
                if (result.notOnAccountNames.isNotEmpty()) {
                    Text(
                        text = if (isAr) "سجلات غير مرتبطة بحساب محلي: ${result.notOnAccountNames.size}" else "Rows with no linked local account: ${result.notOnAccountNames.size}",
                        color = Color(0xFFC62828)
                    )
                    result.notOnAccountNames.forEach { name ->
                        Text(text = "• $name", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828))
                    }
                }
                if (result.updated == 0) {
                    Text(
                        text = if (isAr) "لم تُجرَ أي تغييرات." else "No changes were made.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(if (isAr) "إغلاق" else "Close")
            }
        }
    )
}
