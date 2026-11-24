package id.bgap.speaker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: BgapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Root(vm) }
    }
}

@Composable
private fun Root(vm: BgapViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    fun granted() = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    var hasPerm by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasPerm = ok
        if (ok) { vm.refreshBonded(); vm.autoConnect() }
    }
    LaunchedEffect(Unit) {
        if (hasPerm) { vm.refreshBonded(); vm.autoConnect() }
        else launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    BgapTheme(ui.theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppScreen(vm, ui, hasPerm) { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        }
    }
}
