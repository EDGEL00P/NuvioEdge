package com.nuvio.tv.ui.components.sidebar

import androidx.compose.ui.graphics.vector.ImageVector

data class SidebarItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

data class DrawerItem(
    val route: String,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null
)