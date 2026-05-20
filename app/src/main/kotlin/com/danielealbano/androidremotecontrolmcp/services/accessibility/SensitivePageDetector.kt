package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pure, dependency-free detector that decides whether the foreground screen should be
 * treated as **sensitive** — meaning screenshot capture and accessibility tree dumps
 * must redact their output rather than expose real data.
 *
 * Two independent layers; either triggers sensitivity:
 *  1. **Per-node**: any node in the parsed tree is a password field
 *     ([AccessibilityNodeInfo.isPassword] true, or [AccessibilityNodeInfo.getInputType]
 *     containing one of the password input-type bits).
 *  2. **Per-app**: the foreground package name matches one of [SENSITIVE_PACKAGES]
 *     (Chinese banking + payment apps; conservative exact-match list).
 *
 * No Hilt — call sites construct the placeholder strings and decide what to do (skip
 * vs redact) when [isSensitiveScreen] returns true.
 */
object SensitivePageDetector {
    /** Placeholder text returned by callers in place of real content. */
    const val PLACEHOLDER: String = "[sensitive page hidden]"

    /**
     * Exact-match foreground package names treated as always-sensitive. Conservative —
     * we don't prefix-match because that risks false positives on third-party launchers
     * or app stores.
     */
    val SENSITIVE_PACKAGES: Set<String> =
        setOf(
            // Super-apps with embedded payment flows
            "com.tencent.mm", // WeChat
            "com.eg.android.AlipayGphone", // Alipay
            // Card networks
            "com.unionpay", // UnionPay (云闪付 may also use com.unionpay.tsmservice etc.)
            // Major Chinese banks
            "com.icbc", // ICBC
            "com.ccb", // China Construction Bank
            "com.boc.bocsoft", // Bank of China (legacy)
            "com.chinamworld.bocmbci", // Bank of China (mobile)
            "com.android.bankabc", // Agricultural Bank of China
            "com.cmbchina.ccd.pluto.cmbActivity", // China Merchants Bank
            // E-commerce / fintech wallets
            "com.jdjr.mobilepay", // JD Pay
        )

    /**
     * Returns true if the screen should be treated as sensitive and its content redacted.
     *
     * Either layer can trigger:
     *  - [packageName] matches [SENSITIVE_PACKAGES]
     *  - any node in the [rootNode] subtree is a password field
     *
     * Safe to call with nulls: a null root and a null/unknown package returns false
     * (we can't prove sensitivity, so we don't redact — callers must already gate by
     * permissions). The node scan is bounded by [MAX_SCAN_NODES] to avoid pathological
     * deeply-nested trees blocking the caller; reaching that bound returns false.
     */
    fun isSensitiveScreen(
        rootNode: AccessibilityNodeInfo?,
        packageName: String?,
    ): Boolean {
        if (packageName != null && packageName in SENSITIVE_PACKAGES) {
            return true
        }
        if (rootNode != null && containsPasswordField(rootNode)) {
            return true
        }
        return false
    }

    /**
     * Returns true if [node] or any descendant (up to [MAX_SCAN_NODES] nodes visited)
     * is a password field. Iterative BFS so we don't blow the stack on deep trees.
     */
    private fun containsPasswordField(node: AccessibilityNodeInfo): Boolean {
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val current = queue.removeFirst()
            visited++
            if (isPasswordNode(current)) {
                return true
            }
            val childCount = current.childCount
            for (i in 0 until childCount) {
                val child = current.getChild(i) ?: continue
                queue.add(child)
            }
        }
        return false
    }

    private fun isPasswordNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val inputType = node.inputType
        if (inputType == 0) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    /** Defensive bound on the per-node scan. Real screens have ~hundreds of nodes. */
    private const val MAX_SCAN_NODES = 5_000
}
