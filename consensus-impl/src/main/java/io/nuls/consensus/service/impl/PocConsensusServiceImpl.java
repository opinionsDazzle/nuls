package io.nuls.consensus.service.impl;

import io.nuls.account.entity.Account;
import io.nuls.account.service.intf.AccountService;
import io.nuls.consensus.constant.PocConsensusConstant;
import io.nuls.consensus.entity.member.Agent;
import io.nuls.consensus.entity.member.Delegate;
import io.nuls.consensus.entity.ConsensusAccount;
import io.nuls.consensus.entity.ConsensusStatusInfo;
import io.nuls.consensus.entity.params.QueryConsensusAccountParam;
import io.nuls.consensus.entity.tx.PocExitConsensusTransaction;
import io.nuls.consensus.entity.tx.PocJoinConsensusTransaction;
import io.nuls.consensus.entity.tx.RegisterAgentTransaction;
import io.nuls.consensus.event.ExitConsensusEvent;
import io.nuls.consensus.event.JoinConsensusEvent;
import io.nuls.consensus.event.RegisterAgentEvent;
import io.nuls.consensus.entity.params.JoinConsensusParam;
import io.nuls.consensus.service.cache.ConsensusCacheService;
import io.nuls.consensus.service.intf.ConsensusService;
import io.nuls.core.chain.entity.Na;
import io.nuls.core.chain.entity.NulsDigestData;
import io.nuls.core.chain.entity.Transaction;
import io.nuls.core.constant.ErrorCode;
import io.nuls.core.context.NulsContext;
import io.nuls.core.exception.NulsRuntimeException;
import io.nuls.core.utils.log.Log;
import io.nuls.event.bus.event.service.intf.EventService;
import io.nuls.ledger.entity.tx.LockNulsTransaction;
import io.nuls.ledger.entity.tx.UnlockNulsTransaction;
import io.nuls.ledger.service.intf.LedgerService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * @author Niels
 * @date 2017/11/9
 */
public class PocConsensusServiceImpl implements ConsensusService {

    private static final ConsensusService INSTANCE = new PocConsensusServiceImpl();
    private AccountService accountService = NulsContext.getInstance().getService(AccountService.class);
    private EventService eventService = NulsContext.getInstance().getService(EventService.class);
    private LedgerService ledgerService = NulsContext.getInstance().getService(LedgerService.class);
    private ConsensusCacheService consensusCacheService = ConsensusCacheService.getInstance();

    private PocConsensusServiceImpl() {
    }

    public static ConsensusService getInstance() {
        return INSTANCE;
    }

    private void registerAgent(Agent delegate, Account account, String password) throws IOException {
        RegisterAgentEvent event = new RegisterAgentEvent();
        RegisterAgentTransaction tx = new RegisterAgentTransaction();
        tx.setTxData(delegate);
        LockNulsTransaction lockNulsTransaction = ledgerService.createLockNulsTx(account.getAddress().toString(), password, delegate.getDeposit());
        tx.setLockNulsTransaction(lockNulsTransaction);
        tx.setHash(NulsDigestData.calcDigestData(tx.serialize()));
        tx.setSign(accountService.signData(tx.getHash(), account, password));
        ledgerService.cacheTx(tx);
        event.setEventBody(tx);
        eventService.broadcastHashAndCache(event);
    }

    private void joinTheConsensus(Account account, String password, double amount, String agentAddress) throws IOException {
        JoinConsensusEvent event = new JoinConsensusEvent();
        PocJoinConsensusTransaction tx = new PocJoinConsensusTransaction();
        ConsensusAccount<Delegate> ca = new ConsensusAccount<>();
        ca.setAddress(account.getAddress());
        Delegate delegate = new Delegate();
        delegate.setDelegateAddress(agentAddress);
        delegate.setDeposit(Na.parseNa(amount));
        ca.setExtend(delegate);
        tx.setTxData(ca);
        LockNulsTransaction lockNulsTransaction = ledgerService.createLockNulsTx(account.getAddress().toString(), password, delegate.getDeposit());
        tx.setLockNulsTransaction(lockNulsTransaction);
        tx.setHash(NulsDigestData.calcDigestData(tx.serialize()));
        tx.setSign(accountService.signData(tx.getHash(), account, password));
        ledgerService.cacheTx(tx);
        event.setEventBody(tx);
        eventService.broadcastHashAndCache(event);
    }

    @Override
    public void exitTheConsensus(NulsDigestData joinTxHash, String password) {
        PocJoinConsensusTransaction joinTx = (PocJoinConsensusTransaction) ledgerService.getTransaction(joinTxHash);
        if (null == joinTx) {
            throw new NulsRuntimeException(ErrorCode.ACCOUNT_NOT_EXIST, "address:" + joinTx.getTxData().getAddress().toString());
        }
        Account account = this.accountService.getAccount(joinTx.getTxData().getAddress().toString());
        if (null == account) {
            throw new NulsRuntimeException(ErrorCode.ACCOUNT_NOT_EXIST, "address:" + joinTx.getTxData().getAddress().toString());
        }
        if (!account.validatePassword(password)) {
            throw new NulsRuntimeException(ErrorCode.PASSWORD_IS_WRONG);
        }
        ExitConsensusEvent event = new ExitConsensusEvent();
        PocExitConsensusTransaction tx = new PocExitConsensusTransaction();
        tx.setTxData(joinTxHash);
        UnlockNulsTransaction unlockNulsTransaction = this.ledgerService.createUnlockTx(joinTx.getLockNulsTransaction());
        tx.setUnlockNulsTransaction(unlockNulsTransaction);
        try {
            tx.setHash(NulsDigestData.calcDigestData(tx.serialize()));
        } catch (IOException e) {
            Log.error(e);
            throw new NulsRuntimeException(ErrorCode.HASH_ERROR, e);
        }
        tx.setSign(accountService.signData(tx.getHash(), account, password));
        event.setEventBody(tx);
        eventService.broadcastHashAndCache(event);
    }

    @Override
    public List<ConsensusAccount> getConsensusAccountList(String address, String agentAddress) {
        QueryConsensusAccountParam param = new QueryConsensusAccountParam();
        param.setAddress(address);
        param.setAgentAddress(agentAddress);
        List<ConsensusAccount> list = consensusCacheService.getConsensusAccountList(param);
        return list;
    }

    @Override
    public ConsensusStatusInfo getConsensusInfo(String address) {
        return consensusCacheService.getConsensusStatusInfo(address);
    }

    @Override
    public Na getTxFee(long blockHeight, Transaction tx) {
        long x = blockHeight / PocConsensusConstant.BLOCK_COUNT_OF_YEAR + 1;
        return PocConsensusConstant.TRANSACTION_FEE.div(x);
    }


    public double getDelegateFee(long blockHeight, Transaction tx) {
//        long x = blockHeight / PocConsensusConstant.BLOCK_COUNT_OF_YEAR + 1;
        //todo Dynamic adjustment of commission
        return PocConsensusConstant.DEFAULT_COMMISSION_RATE;
    }

    @Override
    public void joinTheConsensus(String address, String password, Map<String, Object> paramsMap) {
        Account account = this.accountService.getAccount(address);
        if (null == account) {
            throw new NulsRuntimeException(ErrorCode.FAILED, "The account is not exist,address:" + address);
        }
        if (paramsMap == null || paramsMap.size() < 2) {
            throw new NulsRuntimeException(ErrorCode.NULL_PARAMETER);
        }
        if (!account.validatePassword(password)) {
            throw new NulsRuntimeException(ErrorCode.PASSWORD_IS_WRONG);
        }
        JoinConsensusParam params = new JoinConsensusParam(paramsMap);
        if (params.getCommissionRate() != null) {
            Agent delegate = new Agent();
            delegate.setDelegateAddress(params.getAgentAddress());
            delegate.setDeposit(Na.parseNa(params.getDeposit()));
            delegate.setCommissionRate(params.getCommissionRate());
            delegate.setIntroduction(params.getIntroduction());
            try {
                this.registerAgent(delegate, account, password);
            } catch (IOException e) {
                throw new NulsRuntimeException(e);
            }
            return;
        }
        try {
            this.joinTheConsensus(account, password, params.getDeposit(), params.getAgentAddress());
        } catch (IOException e) {
            throw new NulsRuntimeException(e);
        }
    }

}
