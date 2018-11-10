package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.POUNDS
import java.lang.IllegalStateException

/**
 * Change the status of a [Milestone] in a [JobState] from [MilestoneStatus.STARTED] to [MilestoneStatus.COMPLETED].
 *
 * Should be run by the contractor.
 *
 * @param linearId the [JobState] to update.
 * @param milestoneIndex the index of the [Milestone] to be updated in the [JobState].
 */
@InitiatingFlow
@StartableByRPC
class CompleteMilestoneFlow(val linearId: UniqueIdentifier, val milestoneIndex: Int) : FlowLogic<UniqueIdentifier>() {

    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): UniqueIdentifier {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(
                linearId = listOf(linearId))
        val results = serviceHub.vaultService.queryBy<JobState>(queryCriteria)
        val inputStateAndRef = results.states.singleOrNull()
                ?: throw FlowException("There is no JobState with linear ID $linearId")
        val inputState = inputStateAndRef.state.data

        if (inputState.contractor != ourIdentity) throw IllegalStateException("The contractor must start this flow.")

        val newRequestedAmount = inputState.grossCumulativeAmount
        val newNetMilestonePayment = newRequestedAmount - inputState.retentionPercentage

        val updatedMilestones = inputState.milestones.toMutableList()
        updatedMilestones[milestoneIndex] = updatedMilestones[milestoneIndex].copy(status = MilestoneStatus.COMPLETED, requestedAmount = newRequestedAmount.POUNDS , netMilestonePayment = newNetMilestonePayment.POUNDS)

        val newNetCumulativeValue = inputState.netCumulativeValue - inputState.grossCumulativeAmount

        val jobState = inputState.copy(milestones = updatedMilestones, netCumulativeValue = newNetCumulativeValue)

        val finishJobCommand = Command(
                JobContract.Commands.FinishMilestone(milestoneIndex),
                listOf(ourIdentity.owningKey))

        val transactionBuilder = TransactionBuilder(inputStateAndRef.state.notary)
                .addInputState(inputStateAndRef)
                .addOutputState(jobState, JobContract.ID)
                .addCommand(finishJobCommand)

        transactionBuilder.verify(serviceHub)

        val signedTransaction = serviceHub.signInitialTransaction(transactionBuilder)

        subFlow(FinalityFlow(signedTransaction))

        return jobState.linearId
    }
}