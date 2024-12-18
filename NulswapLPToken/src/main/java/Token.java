import io.nuls.contract.sdk.Address;
import io.nuls.contract.sdk.Event;
import io.nuls.contract.sdk.annotation.Required;
import io.nuls.contract.sdk.annotation.View;

import java.math.BigInteger;

public interface Token {

    @View
    String name();

    @View
    String symbol();

    @View
    int decimals();

    @View
    BigInteger totalSupply();

    @View
    BigInteger balanceOf(@Required Address owner);

    boolean transfer(@Required Address to, @Required BigInteger value);

    boolean transferFrom(@Required Address from, @Required Address to, @Required BigInteger value);

    boolean approve(@Required Address spender, @Required BigInteger value);

    @View
    BigInteger allowance(@Required Address owner, @Required Address spender);

    class TransferEvent implements Event {

        private Address from;

        private Address to;

        private BigInteger value;

        public TransferEvent(Address from, @Required Address to, @Required BigInteger value) {
            this.from = from;
            this.to = to;
            this.value = value;
        }

    }

    class ApprovalEvent implements Event {

        private Address owner;

        private Address spender;

        private BigInteger value;

        public ApprovalEvent(@Required Address owner, @Required Address spender, @Required BigInteger value) {
            this.owner = owner;
            this.spender = spender;
            this.value = value;
        }

    }

}