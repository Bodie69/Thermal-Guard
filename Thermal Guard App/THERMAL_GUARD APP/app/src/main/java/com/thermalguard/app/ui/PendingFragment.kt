package com.thermalguard.app.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ValueEventListener
import com.thermalguard.app.R
import com.thermalguard.app.data.ThermalRepository
import com.thermalguard.app.databinding.FragmentPendingBinding
import com.thermalguard.app.model.StaffRequest

class PendingFragment : Fragment() {
    interface Callback { fun refreshAccount(); fun signOut() }

    private var callback: Callback? = null
    private var _binding: FragmentPendingBinding? = null
    private val repo = ThermalRepository()
    private var requestListener: ValueEventListener? = null
    private var disabled = false
    private var approvalHandled = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? Callback
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View = FragmentPendingBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = _binding ?: return
        disabled = arguments?.getBoolean("disabled") == true
        val user = FirebaseAuth.getInstance().currentUser
        b.tvAccount.text = user?.displayName?.takeIf { it.isNotBlank() } ?: user?.email ?: "Signed-in user"
        step(b.tvStep1Mark, b.tvStep1, Step.DONE, "${signInMethod()} identity verified")
        renderRows(null)

        b.btnRefresh.setOnClickListener { callback?.refreshAccount() }
        b.btnLogout.setOnClickListener { callback?.signOut() }
        b.btnRequestAgain.setOnClickListener {
            val current = _binding ?: return@setOnClickListener
            current.btnRequestAgain.isEnabled = false
            repo.submitPendingStaff(force = true) { ok, err ->
                val latest = _binding ?: return@submitPendingStaff
                latest.btnRequestAgain.isEnabled = true
                if (!ok) latest.info.text = err ?: "Unable to submit staff access request."
            }
        }

        if (disabled) {
            renderDisabled()
        } else {
            requestListener = repo.observeMyStaffRequest(::renderRequest) { error ->
                _binding?.info?.text = error
            }
        }
    }

    private fun renderDisabled() {
        val b = _binding ?: return
        b.title.text = "Account Disabled"
        b.tvStatusLabel.text = "ACCESS DISABLED"
        pill(R.color.tg_red, R.drawable.bg_pill_red)
        b.info.text = "Your Thermal Guard access is currently disabled. Contact the administrator if you need access restored."
        b.btnRefresh.visibility = View.GONE
        b.btnRequestAgain.visibility = View.GONE
        step(b.tvStep2Mark, b.tvStep2, Step.DONE, "Staff account created")
        step(b.tvStep3Mark, b.tvStep3, Step.FAILED, "Access disabled by an administrator")
        renderRows(null, accessOverride = "Disabled")
    }

    private enum class Step { DONE, ACTIVE, FAILED }

    private fun step(mark: TextView, label: TextView, state: Step, text: String) {
        val (symbol, color) = when (state) {
            Step.DONE -> "✓" to R.color.tg_green
            Step.ACTIVE -> "•" to R.color.tg_amber
            Step.FAILED -> "✕" to R.color.tg_red
        }
        mark.text = symbol
        mark.setTextColor(mark.context.getColor(color))
        label.text = text
        label.setTextColor(label.context.getColor(if (state == Step.DONE) R.color.tg_text else color))
    }

    private fun pill(textColor: Int, background: Int) {
        val b = _binding ?: return
        b.tvStatusLabel.setTextColor(b.root.context.getColor(textColor))
        b.tvStatusLabel.setBackgroundResource(background)
    }

    private fun signInMethod(): String {
        val providers = FirebaseAuth.getInstance().currentUser?.providerData.orEmpty().map { it.providerId }
        return if ("google.com" in providers) "Google" else "Email"
    }

    private fun renderRows(request: StaffRequest?, accessOverride: String? = null) {
        val b = _binding ?: return
        val user = FirebaseAuth.getInstance().currentUser
        val status = accessOverride ?: request?.status?.ifBlank { null }?.replaceFirstChar { it.uppercase() } ?: "Pending"
        val statusKey = when (status.lowercase()) {
            "approved" -> "ONLINE"
            "pending" -> "WARNING"
            else -> "OFFLINE"
        }
        UiUtils.bindDetails(b.accountRows, buildList {
            add(DetailRow("Name", user?.displayName?.ifBlank { null } ?: request?.displayName?.ifBlank { null } ?: "--"))
            add(DetailRow("Email", user?.email ?: request?.email?.ifBlank { null } ?: "--"))
            add(DetailRow("Sign-in method", signInMethod()))
            add(DetailRow(if (accessOverride != null) "Access" else "Request status", status, UiUtils.statusColor(b.root.context, statusKey)))
            request?.requestedAt?.takeIf { it > 0L }?.let { add(DetailRow("Requested on", UiUtils.fullTime(it))) }
            request?.reviewedAt?.takeIf { it > 0L }?.let { add(DetailRow("Reviewed on", UiUtils.fullTime(it))) }
        })
    }

    private fun renderRequest(request: StaffRequest?) {
        val b = _binding ?: return
        renderRows(request)
        if (request == null) {
            step(b.tvStep2Mark, b.tvStep2, Step.ACTIVE, "Recording staff request…")
        } else {
            step(b.tvStep2Mark, b.tvStep2, Step.DONE, "Staff request recorded")
        }
        when (request?.status?.lowercase()) {
            "approved" -> {
                b.tvStatusLabel.text = "APPROVED"
                pill(R.color.tg_green, R.drawable.bg_pill_green)
                step(b.tvStep3Mark, b.tvStep3, Step.DONE, "Approved by an administrator")
                b.title.text = "Access Approved"
                b.info.text = "Your staff account has been approved. Opening Thermal Guard…"
                b.btnRequestAgain.visibility = View.GONE
                if (!approvalHandled) {
                    approvalHandled = true
                    b.root.post { if (_binding != null) callback?.refreshAccount() }
                }
            }
            "rejected" -> {
                b.tvStatusLabel.text = "REQUEST REJECTED"
                pill(R.color.tg_red, R.drawable.bg_pill_red)
                step(b.tvStep3Mark, b.tvStep3, Step.FAILED, "Rejected by an administrator")
                b.title.text = "Access Request Rejected"
                b.info.text = "The administrator rejected this request. You can submit a new request if access is still required."
                b.btnRequestAgain.text = "Request access again"
                b.btnRequestAgain.visibility = View.VISIBLE
                b.btnRefresh.visibility = View.VISIBLE
            }
            "removed" -> {
                b.tvStatusLabel.text = "ACCESS REMOVED"
                pill(R.color.tg_amber, R.drawable.bg_pill_amber)
                step(b.tvStep3Mark, b.tvStep3, Step.FAILED, "Access removed by an administrator")
                b.title.text = "Staff Access Removed"
                b.info.text = "Your previous app access was removed by an administrator. You may request staff access again."
                b.btnRequestAgain.text = "Request access again"
                b.btnRequestAgain.visibility = View.VISIBLE
                b.btnRefresh.visibility = View.VISIBLE
            }
            else -> {
                b.tvStatusLabel.text = "PENDING ADMIN APPROVAL"
                pill(R.color.tg_amber, R.drawable.bg_pill_amber)
                step(b.tvStep3Mark, b.tvStep3, Step.ACTIVE, "Waiting for administrator approval")
                b.title.text = "Registration Submitted"
                b.info.text = "Your Google account is registered as a staff request. An administrator must approve it before live monitoring and siren controls become available."
                b.btnRequestAgain.visibility = View.GONE
                b.btnRefresh.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        requestListener?.let(repo::removeMyStaffRequestObserver)
        requestListener = null
        _binding = null
        super.onDestroyView()
    }

    override fun onDetach() {
        callback = null
        super.onDetach()
    }

    companion object {
        fun newInstance(disabled: Boolean) = PendingFragment().apply {
            arguments = Bundle().apply { putBoolean("disabled", disabled) }
        }
    }
}
