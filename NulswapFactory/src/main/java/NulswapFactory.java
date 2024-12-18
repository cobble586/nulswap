import io.nuls.contract.sdk.*;
import io.nuls.contract.sdk.Address;
import io.nuls.contract.sdk.Contract;
import io.nuls.contract.sdk.Msg;
import io.nuls.contract.sdk.Utils;
import io.nuls.contract.sdk.annotation.Payable;
import io.nuls.contract.sdk.annotation.Required;
import io.nuls.contract.sdk.annotation.View;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;

/**
 * @title   Nuls Fcatory Contract
 *
 * @notice  Issues a new pair and stores it
 *
 * @author  Pedro G. S. Ferreira
 *
 */
public class NulswapFactory extends Ownable implements Contract{

    private Address feeTo;                                                                                  //
    private Address feeToSetter;                                                                            //
    private static Address BURNER_ADDR = new Address("NULSd6HgsVSzCAJwLYBjvfP3NwbKCvV525GWn");              // Burner Address Contract

    private Map<Address, Map<Address, Address>> getPair = new HashMap<Address, Map<Address, Address>>();    // Token Pair Mapping

    private List<Address> allPairs = new ArrayList<Address>();                                              // All Pairs List

    // Constructor
    public NulswapFactory(Address _feeToSetter){
        feeToSetter = _feeToSetter;
        feeTo       = _feeToSetter;
    }

    /**
     * Creates a new pair and stores it
     *
     * @param tokenA Token A Contract Address
     * @param tokenB Token B Contract Address
     * */
    public Address createPair(Address tokenA, Address tokenB){

        // TokenA cannot be equal to TokenB
        require(!tokenA.equals(tokenB), "NulswapV3: IDENTICAL_ADDRESSES");

        // Find the correct order of the tokens
        Address token0, token1;
        if(tokenA.hashCode() < tokenB.hashCode()){
            token0 = tokenA;
            token1 = tokenB;
        }else{
            token0 = tokenB;
            token1 = tokenA;
        }

        // Token0 cannot be null
        require(token0 != null, "NulswapV3: ZERO_ADDRESS");

        Map<Address, Address> ownerAllowed = getPair.get(token0);

        require(getPair.get(token0) == null || getPair.get(token0).get(token1) == null, "NulswapV3: PAIR_EXISTS"); // single check is sufficient


        String pairAddr =  Utils.deploy(new String[]{ "pair", token0.toString(), token1.toString()}, new Address("NULSd6Hgw916TK3TdH7i6ashb317VQkjxAiZD"), new String[]{});
        Address pair = new Address(pairAddr);

        initialize(pair, token0, token1);

        Map<Address, Address> tkn0tkn1 = getPair.get(token0) != null ? getPair.get(token0) : new HashMap<>();
        Map<Address, Address> tkn1tkn0 = getPair.get(token1) != null ? getPair.get(token1) : new HashMap<>();

        tkn0tkn1.put(token1, pair);
        tkn1tkn0.put(token0, pair);
        getPair.put(token0, tkn0tkn1);
        getPair.put(token1, tkn1tkn0);
        allPairs.add(pair);

        //
        emit(new PairCreatedEvent(token0, token1, pair, allPairs.size()));
        return pair;
    }

    private void initialize(@Required Address pair, @Required Address token0, @Required Address token1){
        String[][] argsM = new String[][]{new String[]{token0.toString()}, new String[]{token1.toString()}};
        pair.callWithReturnValue("initialize", "", argsM, BigInteger.ZERO);
    }


    public void setFeeTo(Address _feeTo){
        require(Msg.sender().equals(feeToSetter), "NulswapV3: FORBIDDEN");
        feeTo = _feeTo;
    }

    public void setFeeToSetter(Address _feeToSetter){
        require(Msg.sender().equals(feeToSetter), "NulswapV3: FORBIDDEN");
        feeToSetter = _feeToSetter;
    }

    /**
     *
     *
     * */
    @View
    public Address getFeeTo(){
        return feeTo;
    }

    /**
     * Get Pair Contract
     *
     * @param token0 First Token Contract Address
     * @param token1 Second Token Contract Address
     * */
    @View
    public Address getPair(Address token0,Address token1){
        if(getPair.get(token0) != null) {
            if(getPair.get(token0).get(token1) != null)
                return getPair.get(token0).get(token1);
        }
        return BURNER_ADDR;
    }

    @View
    public int allPairsLength(){
        return allPairs.size();
    }

    class PairCreatedEvent implements Event {

        private Address token0;
        private Address token1;
        private Address pair;
        private Integer numberOfPairs;


        public PairCreatedEvent(@Required Address token0, @Required Address token1, @Required Address pair,  @Required Integer numberOfPairs) {
            this.token0 = token0;
            this.token1 = token1;
            this.pair = pair;
            this.numberOfPairs = numberOfPairs;
        }

    }

}