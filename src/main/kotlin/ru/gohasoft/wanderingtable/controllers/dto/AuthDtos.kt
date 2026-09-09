package ru.gohasoft.wanderingtable.controllers.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

const val PASSWORD_REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}\$"
const val PASSWORD_MESSAGE =
    "Password must be at least 8 characters long and contain at least one digit, uppercase and lowercase character."

data class RegisterRequest(
    @field:NotBlank(message = "Name can't be blank.")
    @field:Size(max = 64, message = "Name must be at most 64 characters long.")
    val name: String,
    @field:Email(message = "Invalid email format.")
    val email: String,
    @field:Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE)
    val password: String
)

/**
 * Отдельный DTO для входа: `name` при логине не используется, а прежний общий `AuthRequest`
 * требовал присылать его обязательным полем.
 */
data class LoginRequest(
    @field:NotBlank(message = "Email can't be blank.")
    val email: String,
    @field:NotBlank(message = "Password can't be blank.")
    val password: String
)

data class RefreshRequest(
    @field:NotBlank(message = "Refresh token can't be blank.")
    val refreshToken: String
)

data class TokenPairResponse(
    val accessToken: String,
    val refreshToken: String
)

const val RESET_CODE_REGEX = "[0-9]{6}"
const val RESET_CODE_MESSAGE = "Reset code must be exactly 6 digits."

data class ForgotPasswordRequest(
    @field:Email(message = "Invalid email format.")
    val email: String
)

data class VerifyResetCodeRequest(
    @field:NotBlank(message = "Email can't be blank.")
    val email: String,
    @field:Pattern(regexp = RESET_CODE_REGEX, message = RESET_CODE_MESSAGE)
    val code: String
)

data class ResetTokenResponse(
    val resetToken: String
)

data class ResetPasswordRequest(
    @field:NotBlank(message = "Reset token can't be blank.")
    val resetToken: String,
    @field:Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE)
    val newPassword: String
)
