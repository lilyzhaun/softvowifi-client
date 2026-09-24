package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*

class CallLaunchRegistryTest {
    @Test fun promotionAliasResolvesCurrentConfirmedBindingAcrossPhases() {
        val registry = registry()
        val initial = assertNotNull(registry.update(state(binding())))
        val confirmed = binding(token = "confirmed")
        val primary = assertNotNull(registry.update(state(confirmed)))
        assertNotEquals(initial, primary)
        assertSame(confirmed, registry.resolve(initial, state(confirmed)))
        assertSame(confirmed, registry.resolve(primary, state(confirmed)))
        val refreshed = binding(token = "confirmed")
        val inCall = state(refreshed, CallPhase.InCall)
        assertEquals(primary, registry.update(inCall))
        assertSame(refreshed, registry.resolve(initial, inCall))
        assertSame(refreshed, registry.resolve(primary, inCall))
        assertNull(registry.resolve("unknown", inCall))
    }

    @Test fun promotionCannotCrossOwnerEpochOrGeneration() {
        listOf(
            binding(owner = "other", token = "confirmed"),
            binding(epoch = "other", token = "confirmed"),
            binding(generation = 2, token = "confirmed"),
        ).forEach { replacement ->
            val registry = registry()
            val old = assertNotNull(registry.update(state(binding())))
            val current = state(replacement)
            val primary = assertNotNull(registry.update(current))
            assertNull(registry.resolve(old, current))
            assertSame(replacement, registry.resolve(primary, current))
        }
    }

    @Test fun confirmedIdentityReplacementDropsAliasAndOldPrimary() {
        listOf(
            binding(token = "next"),
            binding(owner = "other", token = "confirmed"),
            binding(epoch = "other", token = "confirmed"),
        ).forEach { replacement ->
            val registry = registry()
            val alias = assertNotNull(registry.update(state(binding())))
            val oldPrimary = assertNotNull(registry.update(state(binding(token = "confirmed"))))
            val current = state(replacement)
            val primary = assertNotNull(registry.update(current))
            assertNull(registry.resolve(alias, current))
            assertNull(registry.resolve(oldPrimary, current))
            assertSame(replacement, registry.resolve(primary, current))
        }
    }

    @Test fun bindingGenerationInvalidationDropsAlias() {
        val registry = registry()
        val alias = assertNotNull(registry.update(state(binding())))
        registry.update(state(binding(token = "confirmed")))
        val replacement = binding(token = "confirmed", generation = 2)
        val current = state(replacement)
        val primary = assertNotNull(registry.update(current))
        assertNull(registry.resolve(alias, current))
        assertSame(replacement, registry.resolve(primary, current))
    }

    @Test fun terminalOrUnavailableStateClearsAliasBeforeNewDial() {
        listOf(
            state(binding(token = "confirmed"), CallPhase.Idle),
            CallUiState.Loading,
            CallUiState.Unavailable("unavailable"),
            state(binding()).copy(binding = null),
        ).forEach { terminal ->
            val registry = registry()
            val alias = assertNotNull(registry.update(state(binding())))
            val oldPrimary = assertNotNull(registry.update(state(binding(token = "confirmed"))))
            assertNull(registry.update(terminal))
            assertNull(registry.resolve(alias, terminal))
            val next = state(binding(generation = 2))
            val nextKey = assertNotNull(registry.update(next))
            assertNull(registry.resolve(alias, next))
            assertNull(registry.resolve(oldPrimary, next))
            assertSame(next.binding, registry.resolve(nextKey, next))
        }
    }

    @Test fun freshRegistryReconstructsOnlyPrimaryNotProcessLocalAlias() {
        val first = registry()
        val alias = assertNotNull(first.update(state(binding())))
        val current = state(binding(token = "confirmed"), CallPhase.InCall)
        val primary = assertNotNull(first.update(current))
        val restarted = registry()
        assertEquals(primary, restarted.update(current))
        assertSame(current.binding, restarted.resolve(primary, current))
        assertNull(restarted.resolve(alias, current))
        assertNull(restarted.resolve("unknown", current))
        assertNull(restarted.resolve(null, current))
    }

    private fun registry(): CallLaunchRegistry {
        var pendingKey = 0
        return CallLaunchRegistry { binding ->
            binding.token?.let { "${binding.owner}/${binding.epoch}/$it" }
                ?: "pending-${++pendingKey}"
        }
    }

    private fun binding(owner: String = "owner", token: String? = null,
                        generation: Long = 1, epoch: String = "epoch") =
        CallBinding(owner, token, generation, epoch)

    private fun state(binding: CallBinding, phase: CallPhase = CallPhase.Dialing) =
        CallUiState.Ready(phase, CallAudioState.Idle, CallOrigin.ENGINE, binding = binding)
}
