package com.thermalguard.app.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.thermalguard.app.R
import com.thermalguard.app.databinding.FragmentLoginBinding

/**
 * Authentication shell for Thermal Guard.
 *
 * The layout is intentionally light and closely follows the supplied visual reference.
 * Existing Google/Firebase approval behavior stays owned by MainActivity/Repository.
 * Email/password is an additional sign-in path for Firebase Auth users that already exist;
 * first-time staff registration continues to use Google and administrator approval.
 */
class LoginFragment : Fragment() {

    interface Callback {
        fun signInWithGoogle()
        fun onEmailAuthenticated()
    }

    private var callback: Callback? = null
    private var _binding: FragmentLoginBinding? = null
    private val auth by lazy { FirebaseAuth.getInstance() }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? Callback
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View = FragmentLoginBinding.inflate(inflater, container, false)
        .also { _binding = it }
        .root

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = _binding ?: return

        b.btnGoogle.setOnClickListener {
            clearErrors()
            callback?.signInWithGoogle()
        }

        // Staff registration intentionally enters the same trusted Google auth flow.
        // MainActivity then creates /staff_requests/{uid} when there is no approved user.
        b.btnRegisterStaff.setOnClickListener {
            clearErrors()
            callback?.signInWithGoogle()
        }

        b.btnSignIn.setOnClickListener {
            signInWithEmail()
        }

        b.tvForgotPassword.setOnClickListener {
            resetPassword()
        }

        b.editPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                signInWithEmail()
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Keep the reference login screen bright even when the user selected dark mode
        // for the signed-in monitoring screens.
        activity?.window?.let { window ->
            window.statusBarColor = Color.WHITE
            window.navigationBarColor = Color.rgb(248, 252, 255)
            WindowInsetsControllerCompat(window, requireView()).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    override fun onPause() {
        // Restore the app palette for Dashboard/Settings when leaving authentication.
        activity?.window?.let { window ->
            window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.tg_bg)
            window.navigationBarColor = ContextCompat.getColor(requireContext(), R.color.tg_surface)
        }
        super.onPause()
    }

    private fun signInWithEmail() {
        val b = _binding ?: return
        clearErrors()

        val email = b.editEmail.text?.toString()?.trim().orEmpty()
        val password = b.editPassword.text?.toString().orEmpty()

        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            b.tilEmail.error = "Enter a valid email address"
            b.editEmail.requestFocus()
            return
        }

        if (password.isBlank()) {
            b.tilPassword.error = "Enter your password"
            b.editPassword.requestFocus()
            return
        }

        setLoading(true)
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                setLoading(false)
                callback?.onEmailAuthenticated()
            }
            .addOnFailureListener { error ->
                setLoading(false)
                toast(error.localizedMessage ?: "Unable to sign in with email and password")
            }
    }

    private fun resetPassword() {
        val b = _binding ?: return
        clearErrors()

        val email = b.editEmail.text?.toString()?.trim().orEmpty()
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            b.tilEmail.error = "Enter your account email first"
            b.editEmail.requestFocus()
            return
        }

        b.tvForgotPassword.isEnabled = false
        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                _binding?.tvForgotPassword?.isEnabled = true
                if (task.isSuccessful) {
                    toast("Password reset email sent")
                } else {
                    toast(task.exception?.localizedMessage ?: "Unable to send reset email")
                }
            }
    }

    private fun clearErrors() {
        _binding?.tilEmail?.error = null
        _binding?.tilPassword?.error = null
    }

    private fun setLoading(loading: Boolean) {
        val b = _binding ?: return
        b.btnSignIn.isEnabled = !loading
        b.btnGoogle.isEnabled = !loading
        b.btnRegisterStaff.isEnabled = !loading
        b.editEmail.isEnabled = !loading
        b.editPassword.isEnabled = !loading
        b.btnSignIn.text = if (loading) "Signing in…" else getString(R.string.login_sign_in)
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDetach() {
        callback = null
        super.onDetach()
    }
}
