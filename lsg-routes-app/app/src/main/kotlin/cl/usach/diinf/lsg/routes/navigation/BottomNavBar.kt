package cl.usach.diinf.lsg.routes.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy

/** Barra de navegacion inferior con tres tabs principales. */
@Composable
fun BottomNavBar(
    items: List<BottomNavItem>,
    currentDestination: NavDestination?,
    onNavigate: (BottomNavItem) -> Unit,
) {
    NavigationBar {
        items.forEach { item ->
            val selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true
            NavigationBarItem(
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.labelRes)) },
                selected = selected,
                onClick = { onNavigate(item) },
            )
        }
    }
}
