// CombatSolver1 protocol only; uses the existing simulator's selection semantics.
static std::string hexId(const std::string &id) {
    const char *h = "0123456789abcdef";
    std::string out;
    for (unsigned char c : id) { out += h[c >> 4]; out += h[c & 15]; }
    return out;
}
static const char *choiceSource(CardSelectTask task) {
    switch (task) {
        case CardSelectTask::HEADBUTT: case CardSelectTask::LIQUID_MEMORIES_POTION:
        case CardSelectTask::HOLOGRAM: return "discard";
        case CardSelectTask::EXHUME: return "exhaust";
        case CardSelectTask::SECRET_TECHNIQUE: case CardSelectTask::SECRET_WEAPON:
        case CardSelectTask::SEEK: return "draw";
        case CardSelectTask::CODEX: case CardSelectTask::DISCOVERY: return "offer";
        default: return "hand";
    }
}
static CardInstance choiceCard(const BattleContext &bc, int idx) {
    std::string source = choiceSource(bc.cardSelectInfo.cardSelectTask);
    if (source == "discard") return bc.cards.discardPile[idx];
    if (source == "exhaust") return bc.cards.exhaustPile[idx];
    if (source == "draw") return bc.cards.drawPile[idx];
    if (source == "offer") return CardInstance(bc.cardSelectInfo.cards[idx]);
    return bc.cards.hand[idx];
}
static void printChoice(std::ostream &os, const BattleContext &bc, const search::Action &a) {
    const auto task = bc.cardSelectInfo.cardSelectTask;
    const bool multi = a.getActionType() == search::ActionType::MULTI_CARD_SELECT;
    const bool exhume = task == CardSelectTask::EXHUME;
    const bool skip = task == CardSelectTask::CODEX && a.getSelectIdx() == 3;
    std::vector<int> indices;
    if (multi) { for (int i : a.getSelectedIdxs()) indices.push_back(i); }
    else if (!skip) indices.push_back(a.getSelectIdx());
    os << "PLANSELECT v=" << (exhume ? 3 : 2)
       << " task=" << cardSelectTaskStrings[static_cast<int>(task)]
       << " source=" << choiceSource(task) << " multi=" << multi << " skip=" << skip
       << " bits=" << std::hex << a.bits << std::dec << " idxs=";
    if (indices.empty()) os << '-';
    for (size_t i=0; i<indices.size(); ++i) { if(i) os << ','; os << indices[i]; }
    os << " cards=";
    if (indices.empty()) os << '-';
    for (size_t i=0; i<indices.size(); ++i) {
        if(i) os << '|';
        const auto c = choiceCard(bc, indices[i]);
        os << hexId(sts::cardIdToJsonString(c.id)) << ':' << c.getUpgradeCount()
           << ':' << static_cast<int>(c.costForTurn);
    }
    if (exhume) {
        // Indices are simulator pile positions. Java matches the selected card against this
        // complete legal pool by identity; it never treats an engine index as a UI index.
        os << " pool=";
        bool first = true;
        for (int i = 0; i < static_cast<int>(bc.cards.exhaustPile.size()); ++i) {
            const auto &c = bc.cards.exhaustPile[i];
            if (c.getId() == CardId::EXHUME) continue;
            if (!first) os << '|';
            first = false;
            os << i << ':' << hexId(sts::cardIdToJsonString(c.id)) << ':' << c.getUpgradeCount()
               << ':' << static_cast<int>(c.costForTurn);
        }
    }
    os << '\n';
}
