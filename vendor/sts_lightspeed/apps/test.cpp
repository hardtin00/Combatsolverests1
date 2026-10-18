//
// Created by gamerpuppy on 7/8/2021.
//

#include <iostream>
#include <chrono>
#include <cstdint>
#include <thread>
#include <memory>
#include <mutex>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <algorithm>

#include "data_structure/fixed_list.h"
#include "constants/Cards.h"
#include "constants/Events.h"
#include "constants/CardPools.h"
#include "game/Game.h"
#include "game/Map.h"
#include "game/Neow.h"
#include "game/SaveFile.h"
#include "combat/BattleContext.h"
#include "sim/ConsoleSimulator.h"
#include "sim/PrintHelpers.h"
#include "sim/RandomAgent.h"
#include "sim/search/ScumSearchAgent2.h"
#include "sim/search/SimpleAgent.h"
#include "sim/search/Action.h"

#include "sim/search/BattleScumSearcher2.h"

using namespace sts;

#include "choice_protocol.h"

static bool applyReplayAction(sts::BattleContext &bc, std::uint32_t bits);
static void printChoice(std::ostream &os, const sts::BattleContext &bc, const sts::search::Action &a);

static int ascendersBaneCostSentinelCheck() {
    auto fail = [](const char *reason) {
        std::cerr << "ASCENDERS_BANE_CHECK_FAIL reason=" << reason << '\n';
        return 1;
    };

    BattleContext bc;
    bc.outcome = Outcome::UNDECIDED;
    bc.inputState = InputState::PLAYER_NORMAL;
    bc.cards.cardsInHand = 2;
    bc.cards.hand[0] = CardInstance(CardId::ASCENDERS_BANE);
    bc.cards.hand[1] = CardInstance(CardId::DEFEND_RED);
    bc.player.energy = 1;

    const search::Action banePlay(search::ActionType::CARD, 0, 0);
    const search::Action defendPlay(search::ActionType::CARD, 1, 0);
    if (bc.cards.hand[0].cost != -3 || bc.cards.hand[0].costForTurn != -3) {
        return fail("engine_cost_sentinel");
    }
    if (bc.cards.hand[0].canUseOnAnyTarget(bc) || banePlay.isValidAction(bc)) {
        return fail("curse_played_without_blue_candle");
    }
    if (!bc.cards.hand[1].canUseOnAnyTarget(bc) || !defendPlay.isValidAction(bc)) {
        return fail("ordinary_card_rejected");
    }

    CardInstance gameEncodedBane(CardId::ASCENDERS_BANE);
    gameEncodedBane.cost = gameEncodedBane.costForTurn = -2;
    bc.player.energy = 0;
    bc.player.setHasRelic<RelicId::BLUE_CANDLE>(true);
    bc.cards.hand[0] = gameEncodedBane;
    const bool gameEncodingPlayable = banePlay.isValidAction(bc);
    const bool gameEncodingGenerated = bc.cards.hand[0].canUseOnAnyTarget(bc);
    bc.cards.hand[0] = CardInstance(CardId::ASCENDERS_BANE);
    const bool engineEncodingPlayable = banePlay.isValidAction(bc);
    const bool engineEncodingGenerated = bc.cards.hand[0].canUseOnAnyTarget(bc);
    if (!gameEncodingPlayable || !gameEncodingGenerated
            || gameEncodingPlayable != engineEncodingPlayable
            || gameEncodingGenerated != engineEncodingGenerated) {
        return fail("negative_cost_playability_differs");
    }

    auto energyAfterBlueCandleUse = [](const CardInstance &card) {
        BattleContext useContext;
        useContext.player.energy = 0;
        useContext.player.setHasRelic<RelicId::BLUE_CANDLE>(true);
        useContext.cards.cardsInHand = 1;
        useContext.cards.hand[0] = card;
        useContext.cards.hand[0].setUniqueId(1);
        useContext.curCardQueueItem = CardQueueItem(card, 0, 0);
        useContext.useCard();
        return useContext.player.energy;
    };
    if (energyAfterBlueCandleUse(gameEncodedBane) != 0
            || energyAfterBlueCandleUse(bc.cards.hand[0]) != 0) {
        return fail("negative_cost_deducted_energy");
    }

    bc.inputState = InputState::CARD_SELECT;
    bc.cardSelectInfo.cardSelectTask = CardSelectTask::EXHAUST_ONE;
    const auto singleChoices = search::Action::enumerateCardSelectActions(bc);
    const bool baneCanBeSelected = std::find(singleChoices.begin(), singleChoices.end(),
            search::Action(search::ActionType::SINGLE_CARD_SELECT, 0)) != singleChoices.end()
            && search::Action(search::ActionType::SINGLE_CARD_SELECT, 0).isValidAction(bc);
    if (!baneCanBeSelected) {
        return fail("bane_blocked_from_single_exhaust_selection");
    }

    bc.cardSelectInfo.cardSelectTask = CardSelectTask::EXHAUST_MANY;
    bc.cardSelectInfo.pickCount = 2;
    if (!search::Action(search::ActionType::MULTI_CARD_SELECT, 1).isValidAction(bc)) {
        return fail("bane_blocked_from_multi_exhaust_selection");
    }

    bc.cardSelectInfo.cardSelectTask = CardSelectTask::GAMBLE;
    bc.cards.hand[0] = gameEncodedBane;
    const bool gameEncodingDiscardSelectable = search::Action(search::ActionType::MULTI_CARD_SELECT, 1).isValidAction(bc);
    bc.cards.hand[0] = CardInstance(CardId::ASCENDERS_BANE);
    const bool engineEncodingDiscardSelectable = search::Action(search::ActionType::MULTI_CARD_SELECT, 1).isValidAction(bc);
    if (!gameEncodingDiscardSelectable || gameEncodingDiscardSelectable != engineEncodingDiscardSelectable) {
        return fail("negative_cost_discard_selection_differs");
    }

    std::cout << "ASCENDERS_BANE_CHECK_OK engineCost=-3 gameCost=-2 normalPlay=blocked blueCandlePlay=equivalent energyDeduction=none singleExhaust=allowed multiExhaust=allowed gambleDiscard=equivalent ordinaryCard=allowed\n";
    return 0;
}

static int blockModifierCheck() {
    auto runIronWave = [](bool upgraded, int dexterity, bool frail, bool noBlock,
            int expectedBlock, int expectedMonsterHp) {
        BattleContext bc;
        bc.inputState = InputState::PLAYER_NORMAL;
        bc.player.curHp = bc.player.maxHp = 80;
        bc.player.energy = 3;
        bc.player.dexterity = dexterity;
        if (frail) bc.player.buff<PS::FRAIL>(2);
        if (noBlock) bc.player.setHasStatus<PS::NO_BLOCK>(true);

        bc.cards.cardsInHand = 1;
        CardInstance ironWave(CardId::IRON_WAVE, upgraded);
        ironWave.setUniqueId(0);
        bc.cards.hand[0] = ironWave;
        bc.cards.drawPile.push_back(CardInstance(CardId::STRIKE_RED));

        bc.monsters.monsterCount = 1;
        bc.monsters.monstersAlive = 1;
        bc.monsters.arr[0].id = MonsterId::JAW_WORM;
        bc.monsters.arr[0].idx = 0;
        bc.monsters.arr[0].curHp = bc.monsters.arr[0].maxHp = 100;

        const search::Action play(search::ActionType::CARD, 0, 0);
        if (!play.isValidAction(bc)) return false;
        play.execute(bc); // Search and replay_trace both execute through this production path.
        return bc.player.block == expectedBlock
                && bc.monsters.arr[0].curHp == expectedMonsterHp
                && bc.player.energy == 3 - ironWave.costForTurn
                && bc.cards.cardsInHand == 0
                && bc.cards.discardPile.size() == 1
                && bc.cards.discardPile[0].getId() == CardId::IRON_WAVE;
    };

    if (!runIronWave(false, 0, false, false, 5, 95)) {
        std::cerr << "BLOCK_MODIFIER_CHECK_FAIL case=iron_wave_base expected_block=5 expected_hp=95\n";
        return 1;
    }
    if (!runIronWave(true, 0, false, false, 7, 93)) {
        std::cerr << "BLOCK_MODIFIER_CHECK_FAIL case=iron_wave_upgraded expected_block=7 expected_hp=93\n";
        return 1;
    }
    if (!runIronWave(false, 2, true, false, 5, 95)) {
        std::cerr << "BLOCK_MODIFIER_CHECK_FAIL case=dexterity_frail_combo expected_block=5 expected_hp=95\n";
        return 1;
    }
    if (!runIronWave(true, 2, true, false, 6, 93)) {
        std::cerr << "BLOCK_MODIFIER_CHECK_FAIL case=upgraded_dexterity_frail expected_block=6 expected_hp=93\n";
        return 1;
    }
    if (!runIronWave(false, 0, true, false, 3, 95)) {
        std::cerr << "BLOCK_MODIFIER_CHECK_FAIL case=frail_rounding expected_block=3 expected_hp=95\n";
        return 1;
    }
    if (!runIronWave(true, 0, true, false, 5, 93)) {
        std::cerr << "BLOCK_MODIFIER_CHECK_FAIL case=upgraded_frail_rounding expected_block=5 expected_hp=93\n";
        return 1;
    }
    if (!runIronWave(false, 2, false, true, 0, 95)) {
        std::cerr << "BLOCK_MODIFIER_CHECK_FAIL case=no_block expected_block=0 expected_hp=95\n";
        return 1;
    }
    if (!runIronWave(false, -8, true, false, 0, 95)) {
        std::cerr << "BLOCK_MODIFIER_CHECK_FAIL case=negative_dexterity_floor expected_block=0 expected_hp=95\n";
        return 1;
    }

    BattleContext invalid;
    invalid.inputState = InputState::PLAYER_NORMAL;
    invalid.player.curHp = invalid.player.maxHp = 80;
    invalid.player.energy = 0;
    invalid.cards.cardsInHand = 1;
    invalid.cards.hand[0] = CardInstance(CardId::IRON_WAVE);
    invalid.cards.hand[0].setUniqueId(0);
    invalid.monsters.monsterCount = 1;
    invalid.monsters.monstersAlive = 1;
    invalid.monsters.arr[0].id = MonsterId::JAW_WORM;
    invalid.monsters.arr[0].idx = 0;
    invalid.monsters.arr[0].curHp = invalid.monsters.arr[0].maxHp = 100;
    const search::Action unaffordable(search::ActionType::CARD, 0, 0);
    if (unaffordable.isValidAction(invalid)
            || invalid.player.block != 0
            || invalid.monsters.arr[0].curHp != 100
            || invalid.cards.cardsInHand != 1
            || invalid.player.energy != 0) {
        std::cerr << "BLOCK_MODIFIER_CHECK_FAIL case=unaffordable_action_mutated_state\n";
        return 1;
    }

    std::cout << "BLOCK_MODIFIER_CHECK_OK cases=9 fields=block,monster_hp,energy,hand,discard upgraded=true invalid_action=true search_action=true\n";
    return 0;
}

static int exhaustTriggerCheck() {
    auto runExhaust = [](bool monstersAlive, int exhaustCount) {
        BattleContext bc;
        bc.outcome = Outcome::PLAYER_VICTORY;
        bc.inputState = InputState::EXECUTING_ACTIONS;
        bc.player.setStatusValueNoChecks<PS::DARK_EMBRACE>(1);
        bc.player.setStatusValueNoChecks<PS::FEEL_NO_PAIN>(4);
        bc.player.setHasStatus<PS::DARK_EMBRACE>(true);
        bc.player.setHasStatus<PS::FEEL_NO_PAIN>(true);
        bc.cards.drawPile.push_back(CardInstance(CardId::STRIKE_RED));
        bc.cards.drawPile.push_back(CardInstance(CardId::DEFEND_RED));
        bc.cards.cardsInHand = 0;
        bc.monsters.monsterCount = 1;
        bc.monsters.monstersAlive = monstersAlive ? 1 : 0;
        bc.monsters.arr[0].id = MonsterId::JAW_WORM;
        bc.monsters.arr[0].idx = 0;
        bc.monsters.arr[0].curHp = monstersAlive ? 50 : 0;
        bc.monsters.arr[0].maxHp = 50;

        for (int i = 0; i < exhaustCount; ++i) {
            CardInstance card(i == 0 ? CardId::PUMMEL : CardId::SENTINEL);
            card.setUniqueId(static_cast<std::int16_t>(i + 1));
            bc.triggerAndMoveToExhaustPile(card);
        }
        bc.executeActions();
        return bc;
    };

    const auto active = runExhaust(true, 2);
    if (active.player.block != 8 || active.cards.cardsInHand != 2
            || active.cards.drawPile.size() != 0 || active.cards.exhaustPile.size() != 2) {
        std::cerr << "EXHAUST_TRIGGER_CHECK_FAIL reason=active_enemy_triggers block="
                  << active.player.block << " hand=" << active.cards.cardsInHand
                  << " draw=" << active.cards.drawPile.size()
                  << " exhaust=" << active.cards.exhaustPile.size() << '\n';
        return 1;
    }

    const auto terminal = runExhaust(false, 1);
    if (terminal.player.block != 4 || terminal.cards.cardsInHand != 0
            || terminal.cards.drawPile.size() != 2 || terminal.cards.exhaustPile.size() != 1) {
        std::cerr << "EXHAUST_TRIGGER_CHECK_FAIL reason=dark_embrace_draw_after_terminal block="
                  << terminal.player.block << " hand=" << terminal.cards.cardsInHand
                  << " draw=" << terminal.cards.drawPile.size()
                  << " exhaust=" << terminal.cards.exhaustPile.size() << '\n';
        return 1;
    }

    std::cout << "EXHAUST_TRIGGER_CHECK_OK active=2x(FNP4+DE1) terminal=FNP4_only piles=verified\n";
    return 0;
}

static int exhumeCheck() {
    auto fail = [](const char *reason) {
        std::cerr << "EXHUME_CHECK_FAIL reason=" << reason << '\n';
        return 1;
    };
    auto prepare = [](BattleContext &bc, bool withCandidates) {
        bc.outcome = Outcome::UNDECIDED;
        bc.inputState = InputState::PLAYER_NORMAL;
        bc.player.energy = 3;
        bc.cards.cardsInHand = 0;
        bc.cards.exhaustPile.clear();
        bc.cardSelectInfo.cardSelectTask = CardSelectTask::EXHUME;
        CardInstance exhume(CardId::EXHUME, true);
        exhume.setUniqueId(99);
        bc.curCardQueueItem.card = exhume;
        bc.cards.exhaustPile.push_back(exhume); // current Exhume must never be selectable
        if (withCandidates) {
            CardInstance baseStrike(CardId::STRIKE_RED, false);
            baseStrike.setUniqueId(41);
            CardInstance upgradedStrike(CardId::STRIKE_RED, true);
            upgradedStrike.setUniqueId(42);
            CardInstance duplicateStrike(CardId::STRIKE_RED, false);
            duplicateStrike.setUniqueId(43);
            bc.cards.exhaustPile.push_back(baseStrike);
            bc.cards.exhaustPile.push_back(upgradedStrike);
            bc.cards.exhaustPile.push_back(duplicateStrike);
        }
    };

    if (CardInstance(CardId::EXHUME, false).cost != 1
            || CardInstance(CardId::EXHUME, true).cost != 0
            || !doesCardExhaust(CardId::EXHUME, false)
            || !doesCardExhaust(CardId::EXHUME, true)) {
        return fail("cost_or_self_exhaust");
    }

    for (int selectedIdx = 1; selectedIdx <= 3; ++selectedIdx) {
        BattleContext searchBranch;
        prepare(searchBranch, true);
        Actions::ExhumeAction().actFunc(searchBranch);
        const auto choices = search::Action::enumerateCardSelectActions(searchBranch);
        search::BattleScumSearcher2 searcher(searchBranch);
        search::BattleScumSearcher2::Node searchNode;
        searcher.enumerateActionsForNode(searchNode, searchBranch);
        if (searchBranch.inputState != InputState::CARD_SELECT || choices.size() != 3
                || searchNode.edges.size() != choices.size()
                || search::Action(search::ActionType::SINGLE_CARD_SELECT, 0).isValidAction(searchBranch)
                || !search::Action(search::ActionType::SINGLE_CARD_SELECT, 1).isValidAction(searchBranch)
                || !search::Action(search::ActionType::SINGLE_CARD_SELECT, 2).isValidAction(searchBranch)
                || !search::Action(search::ActionType::SINGLE_CARD_SELECT, 3).isValidAction(searchBranch)) {
            return fail("candidate_enumeration_or_self_filter");
        }

        // Each branch uses the production search action and its normal state transition.
        const auto selected = std::find(choices.begin(), choices.end(),
                search::Action(search::ActionType::SINGLE_CARD_SELECT, selectedIdx));
        if (selected == choices.end()) return fail("candidate_branch_missing");
        std::ostringstream selectionProtocol;
        printChoice(selectionProtocol, searchBranch, *selected);
        if (selectionProtocol.str().find("PLANSELECT v=3 task=EXHUME source=exhaust") == std::string::npos
                || selectionProtocol.str().find("idxs=" + std::to_string(selectedIdx) + " cards=") == std::string::npos
                || selectionProtocol.str().find("pool=1:537472696b655f52:0:1|2:537472696b655f52:1:1|3:537472696b655f52:0:1") == std::string::npos
                || selectionProtocol.str().find("bits=" + [&]() {
                    std::ostringstream bits;
                    bits << std::hex << selected->bits;
                    return bits.str();
                }()) == std::string::npos) {
            return fail("choice_protocol_does_not_encode_selected_index");
        }
        selected->execute(searchBranch);

        const int expectedUniqueId = selectedIdx == 1 ? 41 : (selectedIdx == 2 ? 42 : 43);
        const bool expectedUpgraded = selectedIdx == 2;
        bool selectedReturned = false;
        for (int i = 0; i < searchBranch.cards.cardsInHand; ++i) {
            const auto &card = searchBranch.cards.hand[i];
            if (card.getUniqueId() == expectedUniqueId) {
                selectedReturned = card.id == CardId::STRIKE_RED && card.upgraded == expectedUpgraded;
            }
        }
        if (!selectedReturned || searchBranch.cards.exhaustPile.size() != 3
                || searchBranch.cards.exhaustPile[0].getUniqueId() != 99
                || searchBranch.cards.exhaustPile[1].getUniqueId() == expectedUniqueId) {
            return fail("selected_instance_not_moved_exactly_once");
        }

        // replay_trace consumes the same serialized Action bits; exercise that dispatcher too.
        BattleContext replayBranch;
        prepare(replayBranch, true);
        Actions::ExhumeAction().actFunc(replayBranch);
        if (!applyReplayAction(replayBranch, selected->bits)
                || replayBranch.cards.cardsInHand != searchBranch.cards.cardsInHand
                || replayBranch.cards.hand[0].getUniqueId() != searchBranch.cards.hand[0].getUniqueId()
                || replayBranch.cards.hand[0].upgraded != searchBranch.cards.hand[0].upgraded
                || replayBranch.cards.exhaustPile.size() != searchBranch.cards.exhaustPile.size()
                || applyReplayAction(replayBranch, selected->bits)) {
            return fail("search_replay_choice_diverged");
        }
    }

    BattleContext singleton;
    prepare(singleton, false);
    CardInstance onlyCandidate(CardId::BASH, false);
    onlyCandidate.setUniqueId(51);
    singleton.cards.exhaustPile.push_back(onlyCandidate);
    Actions::ExhumeAction().actFunc(singleton);
    const auto singletonChoices = search::Action::enumerateCardSelectActions(singleton);
    if (singleton.inputState != InputState::CARD_SELECT || singletonChoices.size() != 1
            || singletonChoices[0].getSelectIdx() != 1
            || !singletonChoices[0].isValidAction(singleton)) {
        return fail("single_candidate_selection_branch");
    }
    std::ostringstream singletonProtocol;
    printChoice(singletonProtocol, singleton, singletonChoices[0]);
    if (singletonProtocol.str().find("PLANSELECT v=3") == std::string::npos
            || singletonProtocol.str().find("pool=1:42617368:0:2") == std::string::npos) {
        return fail("single_candidate_protocol_identity");
    }
    singletonChoices[0].execute(singleton);
    if (singleton.inputState != InputState::PLAYER_NORMAL || singleton.cards.cardsInHand != 1
            || singleton.cards.hand[0].getUniqueId() != 51 || singleton.cards.hand[0].upgraded
            || singleton.cards.exhaustPile.size() != 1 || singleton.cards.exhaustPile[0].getUniqueId() != 99)
        return fail("single_candidate_selection_execution");

    BattleContext empty;
    prepare(empty, false);
    Actions::ExhumeAction().actFunc(empty);
    if (empty.inputState != InputState::PLAYER_NORMAL || !search::Action::enumerateCardSelectActions(empty).empty()) {
        return fail("self_only_exhaust_pile");
    }

    // ConsoleSimulator reaches this chooser directly instead of going through Action validation.
    empty.chooseExhumeCard(0);
    if (empty.cards.cardsInHand != 0 || empty.cards.exhaustPile.size() != 1
            || empty.cards.exhaustPile[0].getUniqueId() != 99) {
        return fail("direct_self_selection_was_not_rejected");
    }
    empty.chooseExhumeCard(-1);
    empty.chooseExhumeCard(1);
    if (empty.cards.cardsInHand != 0 || empty.cards.exhaustPile.size() != 1) {
        return fail("out_of_range_selection_was_not_rejected");
    }

    BattleContext noExhaust;
    prepare(noExhaust, false);
    noExhaust.cards.exhaustPile.clear();
    Actions::ExhumeAction().actFunc(noExhaust);
    if (noExhaust.inputState != InputState::PLAYER_NORMAL || noExhaust.cards.cardsInHand != 0) {
        return fail("empty_exhaust_pile");
    }

    BattleContext fullHand;
    prepare(fullHand, true);
    fullHand.cards.cardsInHand = CardManager::MAX_HAND_SIZE;
    Actions::ExhumeAction().actFunc(fullHand);
    if (fullHand.inputState != InputState::PLAYER_NORMAL || fullHand.cards.cardsInHand != CardManager::MAX_HAND_SIZE
            || fullHand.cards.exhaustPile.size() != 4) {
        return fail("full_hand_must_not_return_card");
    }

    BattleContext corruption;
    prepare(corruption, false);
    corruption.player.setHasStatus<PS::CORRUPTION>(true);
    CardInstance skill(CardId::POWER_THROUGH, false);
    skill.setUniqueId(61);
    corruption.cards.exhaustPile.push_back(skill);
    Actions::ExhumeAction().actFunc(corruption);
    if (corruption.inputState != InputState::CARD_SELECT
            || !search::Action(search::ActionType::SINGLE_CARD_SELECT, 1).isValidAction(corruption)) {
        return fail("corruption_selection_not_offered");
    }
    search::Action(search::ActionType::SINGLE_CARD_SELECT, 1).execute(corruption);
    if (corruption.cards.cardsInHand != 1 || corruption.cards.hand[0].getUniqueId() != 61
            || corruption.cards.hand[0].costForTurn != -9) {
        return fail("corruption_cost_on_return");
    }

    BattleContext exhaustTriggers;
    exhaustTriggers.outcome = Outcome::UNDECIDED;
    exhaustTriggers.inputState = InputState::EXECUTING_ACTIONS;
    exhaustTriggers.player.buff<PS::FEEL_NO_PAIN>(4);
    exhaustTriggers.player.buff<PS::DARK_EMBRACE>(1);
    exhaustTriggers.monsters.monsterCount = 1;
    exhaustTriggers.monsters.monstersAlive = 1;
    exhaustTriggers.monsters.arr[0].id = MonsterId::JAW_WORM;
    exhaustTriggers.monsters.arr[0].curHp = exhaustTriggers.monsters.arr[0].maxHp = 10;
    CardInstance drawCard(CardId::STRIKE_RED);
    drawCard.setUniqueId(71);
    exhaustTriggers.cards.drawPile.push_back(drawCard);
    CardInstance exhume(CardId::EXHUME, true);
    exhume.setUniqueId(72);
    exhaustTriggers.triggerAndMoveToExhaustPile(exhume);
    exhaustTriggers.executeActions();
    if (exhaustTriggers.player.block != 4 || exhaustTriggers.cards.cardsInHand != 1
            || exhaustTriggers.cards.hand[0].getUniqueId() != 71
            || exhaustTriggers.cards.drawPile.size() != 0
            || exhaustTriggers.cards.exhaustPile.size() != 1
            || exhaustTriggers.cards.exhaustPile[0].getUniqueId() != 72) {
        return fail("exhume_self_exhaust_triggers");
    }

    // Full card-use order: selection happens before Exhume itself exhausts, then FNP/DE resolve.
    BattleContext fullUse;
    fullUse.outcome = Outcome::UNDECIDED;
    fullUse.inputState = InputState::PLAYER_NORMAL;
    fullUse.player.energy = 1;
    fullUse.player.buff<PS::FEEL_NO_PAIN>(4);
    fullUse.player.buff<PS::DARK_EMBRACE>(1);
    fullUse.cards.cardsInHand = 1;
    CardInstance playedExhume(CardId::EXHUME, false);
    playedExhume.setUniqueId(80);
    fullUse.cards.hand[0] = playedExhume;
    CardInstance optionA(CardId::STRIKE_RED, false);
    optionA.setUniqueId(81);
    CardInstance optionB(CardId::BASH, false);
    optionB.setUniqueId(82);
    fullUse.cards.exhaustPile.push_back(optionA);
    fullUse.cards.exhaustPile.push_back(optionB);
    CardInstance drawn(CardId::DEFEND_RED, false);
    drawn.setUniqueId(83);
    fullUse.cards.drawPile.push_back(drawn);
    fullUse.monsters.monsterCount = 1;
    fullUse.monsters.monstersAlive = 1;
    fullUse.monsters.arr[0].id = MonsterId::JAW_WORM;
    fullUse.monsters.arr[0].curHp = fullUse.monsters.arr[0].maxHp = 10;
    fullUse.curCardQueueItem = CardQueueItem(playedExhume, 0, 1);
    fullUse.useCard();
    fullUse.inputState = InputState::EXECUTING_ACTIONS;
    fullUse.executeActions();
    if (fullUse.inputState != InputState::CARD_SELECT || fullUse.cards.exhaustPile.size() != 2
            || !search::Action(search::ActionType::SINGLE_CARD_SELECT, 1).isValidAction(fullUse)) {
        return fail("exhume_choice_before_self_exhaust_timing");
    }
    search::Action(search::ActionType::SINGLE_CARD_SELECT, 1).execute(fullUse);
    bool tookSelectedInstance = false, keptOtherInstance = false, exhaustedPlayedExhume = false, drewAfterReturn = false;
    for (int i = 0; i < fullUse.cards.cardsInHand; ++i) {
        tookSelectedInstance |= fullUse.cards.hand[i].getUniqueId() == 82;
        drewAfterReturn |= fullUse.cards.hand[i].getUniqueId() == 83;
    }
    for (const auto &card : fullUse.cards.exhaustPile) {
        keptOtherInstance |= card.getUniqueId() == 81;
        exhaustedPlayedExhume |= card.getUniqueId() == 80;
    }
    if (fullUse.inputState != InputState::PLAYER_NORMAL || fullUse.player.energy != 0
            || fullUse.player.block != 4 || fullUse.cards.cardsInHand != 2
            || !tookSelectedInstance || !drewAfterReturn || !keptOtherInstance || !exhaustedPlayedExhume) {
        return fail("full_exhume_use_selection_and_triggers");
    }

    std::cout << "EXHUME_CHECK_OK candidates=3 sameIdDistinctInstances=3 searchReplay=matched singletonChoice=verified empty=verified fullHand=verified corruption=verified fullUse=verified FNP=4 DE=1 selfExcluded=1 cost=1/0 exhaust=1/1\n";
    return 0;
}

// ---------------------------------------------------------------------------
// 结构化输出：给 CombatSolver1 的 Java 侧解析用（避免解析人类可读文本）。
//   PLANCARD idx=<手牌下标> id=<游戏内卡牌ID> cost=<费用> upg=<0/1> tgt=<目标下标>
//   PLANPOTION idx=<药水槽> id=<药水ID> tgt=<目标下标>（>5 表示丢弃）
//   PLANSELECT idx=<单张选择下标> / idxs=<多张选择下标,点号分隔>
//   PLANTURNEND n=<第几回合> hp= blk= en= turn= mons=<hp:blk|...>
//   PLANEND hp=<最终生命> maxHp=<最大生命>
//   STATE ...（回放结束时的实机可对比状态）
//   REPLAY_INVALID n=<第几个动作> bits=<十六进制>
// ---------------------------------------------------------------------------

static std::string cardIdJson(sts::CardId id) {
    return sts::cardIdToJsonString(id);
}

static std::string potionIdJson(sts::Potion p) {
    return sts::potionToJsonString(p);
}

static void printMonsterHpList(std::ostream &os, const BattleContext &bc) {
    os << "mons=";
    for (int i = 0; i < bc.monsters.monsterCount; ++i) {
        const auto &m = bc.monsters.arr[i];
        if (i) {
            os << '|';
        }
        os << std::max(0, m.curHp) << ':' << m.block;
    }
}

static void printHandIdList(std::ostream &os, const BattleContext &bc) {
    os << "hand=[";
    for (int i = 0; i < bc.cards.cardsInHand; ++i) {
        if (i) {
            os << ',';
        }
        os << cardIdJson(bc.cards.hand[i].id);
    }
    os << ']';
}

static void printStateLine(std::ostream &os, const BattleContext &bc, const char *tag) {
    os << tag
       << " hp=" << bc.player.curHp
       << " max=" << bc.player.maxHp
       << " blk=" << bc.player.block
       << " en=" << bc.player.energy
       << " turn=" << bc.turn
       << " draw=" << bc.cards.drawPile.size()
       << " disc=" << bc.cards.discardPile.size()
       << " exh=" << bc.cards.exhaustPile.size()
       << ' ';
    printHandIdList(os, bc);
    os << ' ';
    printMonsterHpList(os, bc);
    os << '\n';
}

// Detailed read-only root diagnostics for pairing a real combat root with the
// save-derived engine initialization state. Kept separate from STATE0 so Java
// protocol consumers continue to parse the compact line unchanged.
template <typename Pile>
static void printCardIds(std::ostream &os, const Pile &pile) {
    os << '[';
    for (int i = 0; i < static_cast<int>(pile.size()); ++i) {
        if (i) os << ',';
        os << cardIdJson(pile[i].id);
    }
    os << ']';
}

static void printRootDetails(std::ostream &os, const GameContext &gc, const BattleContext &bc) {
    os << "TRACE0_DETAIL piles.draw=";
    printCardIds(os, bc.cards.drawPile);
    os << " piles.discard=";
    printCardIds(os, bc.cards.discardPile);
    os << " piles.exhaust=";
    printCardIds(os, bc.cards.exhaustPile);
    os << " relics=[";
    for (int i = 0; i < gc.relics.size(); ++i) {
        if (i) os << ',';
        os << relicNames[static_cast<int>(gc.relics.relics[i].id)];
    }
    os << "] potions=[";
    for (int i = 0; i < bc.potionCount; ++i) {
        if (i) os << ',';
        os << potionToJsonString(bc.potions[i]);
    }
    os << "] handDamage=[";
    for (int i = 0; i < bc.cards.cardsInHand; ++i) {
        if (i) os << ',';
        const auto &instance = bc.cards.hand[i];
        Card card(instance.id, instance.upgraded);
        const int damage = card.getType() == CardType::ATTACK
                ? bc.calculateCardDamage(instance, 0, card.getBaseDamage()) : 0;
        os << cardIdJson(instance.id) << ':' << damage << ':'
           << static_cast<int>(instance.costForTurn);
    }
    os << "] energyPerTurn=" << static_cast<int>(bc.player.energyPerTurn)
       << " cardDrawPerTurn=" << static_cast<int>(bc.player.cardDrawPerTurn)
       << " cardsInHand=" << bc.cards.cardsInHand
       << " playerStatus={vuln:" << bc.player.getStatus<PS::VULNERABLE>()
       << ",weak:" << bc.player.getStatus<PS::WEAK>()
       << ",frail:" << bc.player.getStatus<PS::FRAIL>()
       << ",entangled:" << bc.player.getStatus<PS::ENTANGLED>()
       << ",intangible:" << bc.player.getStatus<PS::INTANGIBLE>()
       << ",artifact:" << bc.player.getStatus<PS::ARTIFACT>()
       << ",constricted:" << bc.player.getStatus<PS::CONSTRICTED>()
       << ",platedArmor:" << bc.player.getStatus<PS::PLATED_ARMOR>()
       << ",noBlock:" << bc.player.getStatus<PS::NO_BLOCK>()
       << ",vigor:" << bc.player.getStatus<PS::VIGOR>()
       << ",metallicize:" << bc.player.getStatus<PS::METALLICIZE>()
       << ",feelNoPain:" << bc.player.getStatus<PS::FEEL_NO_PAIN>()
       << ",darkEmbrace:" << bc.player.getStatus<PS::DARK_EMBRACE>()
       << ",demonForm:" << bc.player.getStatus<PS::DEMON_FORM>()
       << ",juggernaut:" << bc.player.getStatus<PS::JUGGERNAUT>()
       << ",combust:" << bc.player.getStatus<PS::COMBUST>()
       << ",rupture:" << bc.player.getStatus<PS::RUPTURE>()
       << ",thorns:" << bc.player.getStatus<PS::THORNS>()
       << ",doubleTap:" << bc.player.getStatus<PS::DOUBLE_TAP>()
       << ",rage:" << bc.player.getStatus<PS::RAGE>()
       << ",brutality:" << bc.player.getStatus<PS::BRUTALITY>()
       << ",barricade:" << bc.player.hasStatus<PS::BARRICADE>()
       << ",corruption:" << bc.player.hasStatus<PS::CORRUPTION>()
       << ",panache:" << bc.player.getStatus<PS::PANACHE>()
       << ",sadistic:" << bc.player.getStatus<PS::SADISTIC>()
       << ",magnetism:" << bc.player.getStatus<PS::MAGNETISM>()
       << ",mayhem:" << bc.player.getStatus<PS::MAYHEM>()
       << ",omega:" << bc.player.getStatus<PS::OMEGA>() << "}"
       << '\n';

    for (int i = 0; i < bc.monsters.monsterCount; ++i) {
        const auto &m = bc.monsters.arr[i];
        const auto move = static_cast<int>(m.moveHistory[0]);
        const auto damage = m.getMoveBaseDamage(bc);
        os << "TRACE0_MONSTER idx=" << i
           << " id=" << monsterIdStrings[static_cast<int>(m.id)]
           << " hp=" << m.curHp << " max=" << m.maxHp << " block=" << m.block
           << " str=" << m.strength << " vuln=" << m.vulnerable << " weak=" << m.weak
           << " dmg=" << (m.isAttacking() ? damage.damage : 0)
           << " hits=" << (m.isAttacking() ? damage.attackCount : 1)
           << " artifact=" << static_cast<int>(m.artifact)
           << " malleable=" << m.getStatus<MS::MALLEABLE>()
           << " platedArmor=" << static_cast<int>(m.platedArmor)
           << " intangible=" << m.getStatus<MS::INTANGIBLE>()
           << " invincible=" << m.getStatus<MS::INVINCIBLE>()
           << " beatOfDeath=" << m.getStatus<MS::BEAT_OF_DEATH>()
           << " timeWarp=" << m.getStatus<MS::TIME_WARP>()
           << " move=" << monsterMoveStrings[move]
           << " alive=" << m.isAlive() << '\n';
    }

    os << "TRACE0_RNG seed=" << gc.seed << " floor=" << gc.floorNum
       << " ai=" << bc.aiRng.counter << " card=" << bc.cardRandomRng.counter
       << " misc=" << bc.miscRng.counter << " hp=" << bc.monsterHpRng.counter
       << " potion=" << bc.potionRng.counter << " shuffle=" << bc.shuffleRng.counter
       << '\n';
}

static void printStructuredAction(std::ostream &os, const BattleContext &bc, const search::Action &a) {
    switch (a.getActionType()) {
        case search::ActionType::CARD: {
            const auto &c = bc.cards.hand[a.getSourceIdx()];
            os << "PLANCARD idx=" << a.getSourceIdx()
               << " id=" << cardIdJson(c.id)
               << " cost=" << static_cast<int>(c.costForTurn)
               << " upg=" << (c.upgraded ? 1 : 0)
               << " tgt=" << a.getTargetIdx() << '\n';
            break;
        }
        case search::ActionType::POTION: {
            os << "PLANPOTION idx=" << a.getSourceIdx()
               << " id=" << potionIdJson(bc.potions[a.getSourceIdx()])
               << " tgt=" << a.getTargetIdx() << '\n';
            break;
        }
        case search::ActionType::SINGLE_CARD_SELECT: {
            printChoice(os, bc, a);
            break;
        }
        case search::ActionType::MULTI_CARD_SELECT: {
            printChoice(os, bc, a);
            break;
        }
        default:
            break;
    }
}

// 搜索并在给定状态上打印计划（人类可读行 + 结构化行）。
static void runSearchAndPrint(BattleContext &bc, long long simulations, int treeDepth) {
    search::BattleScumSearcher2 searcher(bc);

    auto startTime = std::chrono::high_resolution_clock::now();
    searcher.search(simulations);
    auto endTime = std::chrono::high_resolution_clock::now();
    double duration = std::chrono::duration<double>(endTime - startTime).count();

    std::cout << "steps: " << simulations << " search time: " << duration << "s\n";
    std::cout << "best search value: " << searcher.bestActionValue
              << " depth: " << searcher.bestActionSequence.size() << '\n';
    if (searcher.bestActionSequence.empty()) {
        std::cout << "bestActionSequenceIsEmpty" << std::endl;
        std::cout << "ending hp: " << bc.player.curHp << '\n';
        std::cout << "PLANEND hp=" << bc.player.curHp << " maxHp=" << bc.player.maxHp
                  << " turn=" << bc.turn
                  << " outcome=" << static_cast<int>(bc.outcome) << ' ';
        printMonsterHpList(std::cout, bc);
        std::cout << '\n';
        std::cout.flush();
        return;
    }

    int turnNo = 0;
    for (auto bestAction : searcher.bestActionSequence) {
        bestAction.printDesc(std::cout, bc) << '\n';
        printStructuredAction(std::cout, bc, bestAction);
        const bool isEndTurn = bestAction.getActionType() == search::ActionType::END_TURN;
        bestAction.execute(bc);
        if (isEndTurn) {
            ++turnNo;
            std::cout << "PLANTURNEND n=" << turnNo
                      << " hp=" << bc.player.curHp
                      << " blk=" << bc.player.block
                      << " en=" << bc.player.energy
                      << " turn=" << bc.turn
                      << " draw=" << bc.cards.drawPile.size()
                      << " disc=" << bc.cards.discardPile.size()
                      << " exh=" << bc.cards.exhaustPile.size()
                      << ' ';
            printHandIdList(std::cout, bc);
            std::cout << ' ';
            printMonsterHpList(std::cout, bc);
            std::cout << '\n';
        }
    }

    std::cout << "ending hp: " << bc.player.curHp << '\n';
    std::cout << "PLANEND hp=" << bc.player.curHp << " maxHp=" << bc.player.maxHp
              << " turn=" << bc.turn
              << " outcome=" << static_cast<int>(bc.outcome) << ' ';
    printMonsterHpList(std::cout, bc);
    std::cout << '\n';

    searcher.printSearchTree(std::cout, treeDepth);

    std::cout.flush();
}

void printSizes() {
    std::cout << "sizeof Map:" << sizeof(Map) << '\n';
    std::cout << "sizeof Player: " << sizeof(Player) << '\n';
    std::cout << "sizeof Monster: " << sizeof(Monster) << '\n';
    std::cout << "sizeof MonsterGroup : " << sizeof(MonsterGroup) << '\n';
    std::cout << "sizeof CardInstance: " << sizeof(CardInstance) << '\n';
    std::cout << "sizeof CardManager : " << sizeof(CardManager) << '\n';
    std::cout << "sizeof ActionFunction : " << sizeof(ActionFunction) << '\n';
    std::cout << "sizeof ActionQueue<40> : " << sizeof(ActionQueue<40>) << '\n';
    std::cout << "sizeof BattleContext: " << sizeof(BattleContext) << '\n';

    std::cout << "sizeof GameContext: " << sizeof(GameContext) << '\n';
    std::cout << "sizeof Deck: " << sizeof(Deck) << '\n';
    std::cout << "sizeof Card: " << sizeof(Card) << '\n';
    std::cout << "sizeof SelectScreenCard: " << sizeof(SelectScreenCard) << '\n';
}

void playFromSaveFile(const std::string &fname, const std::string &actionFile) {
    CharacterClass cc;
    switch (tolower(fname[0])) {
        case 'i':
            cc = sts::CharacterClass::IRONCLAD;
        default:
            cc = sts::CharacterClass::IRONCLAD;
    }

    SaveFile saveFile = SaveFile::loadFromPath(fname, cc);

    ConsoleSimulator sim;
    sim.setupGameFromSaveFile(saveFile);
    SimulatorContext simContext;
    simContext.quitOnTestFailed = false;



    std::ifstream actionListInputStream(actionFile);

    sim.play(actionListInputStream, std::cout, simContext);
    actionListInputStream.close();

//    simContext.printFirstLine = true;
    simContext.quitCommandGiven = false;
    sim.play(std::cin, std::cout, simContext);
}

void replayActionFile(const GameContext &startState, const std::string &fname) {
    std::ifstream ifs(fname);
    GameContext gc(startState);
    BattleContext bc;


    bool inBattle = false;

    int lineNum = 0;
    std::uint32_t actionBits;
    while (true) {
        if (inBattle) {
            if (bc.outcome != sts::Outcome::UNDECIDED) {
                bc.exitBattle(gc);
                inBattle = false;

            } else {
                ++lineNum;
                ifs >> std::hex >> actionBits;
                search::Action a(actionBits);
                a.printDesc(std::cout, bc) << std::endl;
                a.execute(bc);
            }

        } else {
            if (gc.outcome != GameOutcome::UNDECIDED) {
                break;
            }
            if (gc.screenState == sts::ScreenState::BATTLE) {
                bc = {};
                bc.init(gc);
                inBattle = true;

            } else {
                ++lineNum;
                ifs >> std::hex >> actionBits;
                search::GameAction a(actionBits);
                a.printDesc(std::cout, gc) << std::endl;
                a.execute(gc);
            }
        }
    }
}

struct AgentMtInfo {
    std::mutex m;

    std::uint64_t curSeed;
    std::uint64_t seedStart;
    std::uint64_t seedEnd;

    std::int64_t winCount = 0;
    std::int64_t lossCount = 0;
    std::int64_t floorSum = 0;
    std::int64_t totalSimulations = 0;
};

static int g_searchAscension = 0;
static int g_simulationCount = 5;
static int g_print_level = 0;

void agentMtRunner(AgentMtInfo *info) {
    std::uint64_t seed;
    {
        std::scoped_lock lock(info->m);
        seed = info->curSeed++;
    }

    while(true) {
        if (seed >= info->seedEnd) {
            break;
        }

        GameContext gc(CharacterClass::IRONCLAD, seed, g_searchAscension);
        search::ScumSearchAgent2 agent;
        agent.simulationCountBase = g_simulationCount;
        agent.rng = std::default_random_engine(gc.seed);

        agent.printActions = g_print_level & 0x1;
        agent.printLogs = g_print_level & 0x2;

        agent.playout(gc);

        printOutcome(std::cout, gc);

        {
            std::scoped_lock lock(info->m);
            info->floorSum += gc.floorNum;
            if (gc.outcome == sts::GameOutcome::PLAYER_VICTORY) {
                ++info->winCount;
            } else {
                ++info->lossCount;
            }
            info->totalSimulations += agent.simulationCountTotal;

            seed = info->curSeed++;
        }
    }
}

void agentMt(int threadCount, std::uint64_t startSeed, int playoutCount) {
    auto startTime = std::chrono::high_resolution_clock::now();
    std::vector<std::unique_ptr<std::thread>> threads;

    AgentMtInfo info;
    info.curSeed = startSeed;
    info.seedStart = startSeed;
    info.seedEnd = startSeed + playoutCount;


    if (threadCount == 1) { // doing this for more consistency when benchmarking
        agentMtRunner(&info);

    } else {
        for (int tid = 0; tid < threadCount; ++tid) {
            threads.emplace_back(new std::thread(agentMtRunner, &info));
        }
    }

    for (int tid = 0; tid < threadCount; ++tid) {
        if (threadCount > 1) {
            threads[tid]->join();
        }
    }

    auto endTime = std::chrono::high_resolution_clock::now();
    double duration = std::chrono::duration<double>(endTime-startTime).count();

    std::cout << "w/l: (" << info.winCount  << ", " << info.lossCount << ")"
        << " percentWin: " << static_cast<double>(info.winCount) / playoutCount * 100 << "%"
        << " avgFloorReached: " << static_cast<double>(info.floorSum) / playoutCount << '\n'
        << " totalSimulations: " << info.totalSimulations
        << " avgPerFloor: " << (double)info.totalSimulations/info.floorSum << '\n';

    std::cout << "threads: " << threadCount
              << " playoutCount: " << playoutCount
              << " depth: " << g_simulationCount
        << " asc: " << g_searchAscension
        << " elapsed: " << duration
        << std::endl;
}

int mcts(int argc, const char *argv[]) {
    const auto saveFilePath = argv[2];
    const auto simulationCount = std::stoll(argv[3]);

    SaveFile saveFile = SaveFile::loadFromPath(saveFilePath, sts::CharacterClass::IRONCLAD);
    GameContext gc;
    gc.initFromSave(saveFile);

    std::cout << SeedHelper::getString(gc.seed) << std::endl;

    BattleContext bc = BattleContext();
    bc.init(gc);

    printStateLine(std::cout, bc, "STATE0");
    runSearchAndPrint(bc, simulationCount, 3);
    return 0;
}

static bool applyReplayAction(BattleContext &bc, std::uint32_t bits) {
    if (bc.outcome != Outcome::UNDECIDED) return false;
    const search::Action action(bits);
    if (!action.isValidAction(bc)) return false;
    action.execute(bc);
    return true;
}

// 从存档开战，先按动作文件回放（十六进制 action bits，每行一个），
// 再在回放后的状态上重新搜索。动作文件由 Java 侧记录（含自动执行与手动出牌）。
int replaySearch(int argc, const char *argv[]) {
    const std::string saveFilePath(argv[2]);
    const std::string actionFilePath(argv[3]);
    const auto simulationCount = std::stoll(argv[4]);
    const bool trace = std::string(argv[1]) == "replay_trace";

    SaveFile saveFile = SaveFile::loadFromPath(saveFilePath, sts::CharacterClass::IRONCLAD);
    GameContext gc;
    gc.initFromSave(saveFile);
    BattleContext bc = BattleContext();
    bc.init(gc);

    std::ifstream ifs(actionFilePath);
    if (!ifs) { std::cerr << "Cannot open journal\n"; return 2; }
    if (trace) {
        printStateLine(std::cout, bc, "TRACE0");
        printRootDetails(std::cout, gc, bc);
    }
    std::string line;
    int actionIndex = 0;
    while (std::getline(ifs, line)) {
        std::size_t start = line.find_first_not_of(" \t\r\n");
        if (start == std::string::npos || line[start] == '#') {
            continue;
        }
        std::size_t end = line.find_last_not_of(" \t\r\n");
        std::string hex = line.substr(start, end - start + 1);
        std::uint32_t bits = 0;
        try {
            size_t consumed = 0;
            const auto value = std::stoull(hex, &consumed, 16);
            if (consumed != hex.size() || value > UINT32_MAX) throw std::invalid_argument("action bits");
            bits = static_cast<std::uint32_t>(value);
        } catch (...) {
            std::cout << "REPLAY_INVALID n=" << (actionIndex + 1) << " bits=" << hex << '\n';
            std::cout.flush();
            return 0;
        }
        ++actionIndex;
        if (bc.outcome != Outcome::UNDECIDED) {
            std::cout << "REPLAY_INVALID n=" << actionIndex << " reason=terminal\n";
            return 2;
        }
        if (!applyReplayAction(bc, bits)) {
            std::cout << "REPLAY_INVALID n=" << actionIndex << " bits=" << hex << '\n';
            std::cout.flush();
            return 0;
        }
        if (trace) printStateLine(std::cout, bc, ("TRACE" + std::to_string(actionIndex)).c_str());
    }

    std::cout << "REPLAY_OK n=" << actionIndex << '\n';
    printStateLine(std::cout, bc, "STATE");
    if (trace) return 0;
    if (bc.outcome != Outcome::UNDECIDED) {
        std::cout << "PLANEND hp=" << bc.player.curHp << " maxHp=" << bc.player.maxHp
                  << " turn=" << bc.turn
                  << " outcome=" << static_cast<int>(bc.outcome) << ' ';
        printMonsterHpList(std::cout, bc);
        std::cout << '\n';
        std::cout.flush();
        return 0;
    }

    runSearchAndPrint(bc, simulationCount, 3);
    return 0;
}

int main(int argc, const char* argv[]) {

    if (argc < 2) {
        std::cout << "incorrect arguments" << std::endl;
        return 0;
    }

    const std::string command(argv[1]);

    if (command == "ascenders_bane_cost_check") {
        return ascendersBaneCostSentinelCheck();
    }

    if (command == "block_modifier_check") {
        return blockModifierCheck();
    }

    if (command == "exhaust_trigger_check") {
        return exhaustTriggerCheck();
    }

    if (command == "exhume_check") {
        return exhumeCheck();
    }

    if (command == "protocol") {
        std::cout << "COMBATSOLVER1_PROTOCOL version=2 commands=mcts_save,replay_search,replay_trace,json,json_to_save" << std::endl;
        return 0;
    }

    if (command == "replay") {
        const std::uint64_t seed = std::stoull(argv[2]);
        const int ascension = std::stoi(argv[3]);
        const std::string actionFile(argv[4]);
        replayActionFile(GameContext(sts::CharacterClass::IRONCLAD, seed, ascension), actionFile);

    } else if (command == "save") {
        playFromSaveFile(argv[2], argv[3]);

    } if (command == "agent_mt") { // actually doing tree search now
        const int threadCount(std::stoi(argv[2]));
        const int depthArg = std::stoi(argv[3]);
        const int ascensionIn = std::stoi(argv[4]);
        const std::uint64_t startSeedLong(std::stoull(argv[5]));
        const int playoutCount(std::stoi(argv[6]));
        const int printLevel = std::stoi(argv[7]);
        g_print_level = printLevel;
        g_searchAscension = ascensionIn;
        g_simulationCount = depthArg;

        agentMt(threadCount, startSeedLong, playoutCount);

    } if (command == "simple_agent_mt") { // actually doing tree search now
        const int threadCount(std::stoi(argv[2]));
        const std::uint64_t startSeedLong(std::stoull(argv[3]));
        const int playoutCount(std::stoi(argv[4]));

        bool print = false;
        if (argc > 5) {
            print = true;
        }

        search::SimpleAgent::runAgentsMt(threadCount, startSeedLong, playoutCount, print);

    } else if (command == "json") {
        const std::string saveFilePath(argv[2]);
        const std::string jsonOutPath(argv[3]);
        std::ofstream outFileStream(jsonOutPath);
        outFileStream << SaveFile::getJsonFromSaveFile(saveFilePath);
        outFileStream.close();

    } else if (command == "json_to_save") {
        const std::string jsonInPath(argv[2]);
        const std::string saveFileOutPath(argv[3]);

        std::ifstream jsonIfStream(jsonInPath);
        SaveFile::writeJsonToSaveFile(jsonIfStream, saveFileOutPath);

    }  else if (command == "scum_searcher") {
        const std::uint64_t startSeedLong(std::stoull(argv[2]));
        const int playoutCount(std::stoi(argv[3]));

        for (std::uint64_t seed = startSeedLong; seed < startSeedLong+playoutCount; ++seed) {
//            playRandom4(startSeedLong);
        }

    } else if (command == "mcts_save") {
        return mcts(argc, argv);
    } else if (command == "replay_search" || command == "replay_trace") {
        if (argc < 5) return 2;
        return replaySearch(argc, argv);
    }

    //    printSizes();
//    std::cout << SeedHelper::getString(77) << '\n';
//    playRandom();
//    std::cout << getSeedWithGuardian();
//    replayActionList(argv[1]);

    return 0;
}
