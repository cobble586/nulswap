import io.nuls.contract.sdk.Address;
import io.nuls.contract.sdk.Event;
import io.nuls.contract.sdk.Msg;
import io.nuls.contract.sdk.annotation.Required;
import io.nuls.contract.sdk.annotation.View;

import java.math.BigInteger;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;

/**
 * @author: Long
 * @date: 2019-03-15
 */
public class Ownable {

    /**
     * 合约创建者
     */
    protected Address contractCreator;

    protected Address owner;

    public Ownable() {
        this.owner = Msg.sender();
        this.contractCreator = this.owner;
    }

    @View
    public Address viewOwner() {
        return owner;
    }

    @View
    public String viewContractCreator() {
        return this.contractCreator != null ? this.contractCreator.toString() : "";
    }

    protected void onlyOwner() {
        require(Msg.sender().equals(owner), "Only the owner of the contract can execute it.");
    }

    protected void onlyCreator() {
        require(Msg.sender().equals(contractCreator), "Only the creator of the contract can execute it.");
    }

    /**
     * 转让合约所有权
     *
     * @param newOwner
     */
    public void transferOwnership(Address newOwner) {
        onlyOwner();
        emit(new OwnershipTransferredEvent(owner, newOwner));
        owner = newOwner;
    }

    /**
     * 放弃合约
     */
    public void renounceOwnership() {
        onlyOwner();
        emit(new OwnershipRenouncedEvent(owner));
        owner = null;
    }

    public void transferOtherNRC20(@Required Address nrc20, @Required Address to, @Required BigInteger value) {
        onlyOwner();
        require(!Msg.address().equals(nrc20), "Do nothing by yourself");
        require(nrc20.isContract(), "[" + nrc20.toString() + "] is not a contract address");
        String[][] args = new String[][]{new String[]{Msg.address().toString()}};
        String balance = nrc20.callWithReturnValue("balanceOf", "", args, BigInteger.ZERO);
        require(new BigInteger(balance).compareTo(value) >= 0, "No enough balance");

        String methodName = "transfer";
        String[][] args1 = new String[][]{
                new String[]{to.toString()},
                new String[]{value.toString()}};
        nrc20.call(methodName, "(Address to, BigInteger value) return boolean", args1, BigInteger.ZERO);
    }

    /**
     * 转移owner
     */
    class OwnershipTransferredEvent implements Event {

        //先前拥有者
        private Address previousOwner;

        //新的拥有者
        private Address newOwner;

        public OwnershipTransferredEvent(Address previousOwner, Address newOwner) {
            this.previousOwner = previousOwner;
            this.newOwner = newOwner;
        }

    }


    /**
     * 放弃拥有者
     */
    class OwnershipRenouncedEvent implements Event {

        // 先前拥有者
        private Address previousOwner;

        public OwnershipRenouncedEvent(Address previousOwner) {
            this.previousOwner = previousOwner;
        }

    }

}