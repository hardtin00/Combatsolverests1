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

#include "sim/search/BattleScumSearcher2.h"

using namespace sts;

#include "choice_protocol.h"

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
        search::Action a(bits);
        if (!a.isValidAction(bc)) {
            std::cout << "REPLAY_INVALID n=" << actionIndex << " bits=" << hex << '\n';
            std::cout.flush();
            return 0;
        }
        a.execute(bc);
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
