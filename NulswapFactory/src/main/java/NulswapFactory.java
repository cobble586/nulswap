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
 * @title   Nuls Oracles Revenue Distribution Contract
 *
 * @dev     Allows for users to deposit Nuls Oracle Tokens (ORA)
 *          and earn revenue from the Nuls Oracles Project.
 *          After deposited users will no longer be able to
 *          withdraw the tokens, meanign that by depositing
 *          they are burning their tokens.
 *
 * @author  Pedro G. S. Ferreira
 *
 */
public class NulswapFactory extends Ownable implements Contract{

    private Address feeTo;
    private Address feeToSetter;
    private static Address BURNER_ADDR = new Address("NULSd6HgsVSzCAJwLYBjvfP3NwbKCvV525GWn");

    private Map<Address, Map<Address, Address>> getPair = new HashMap<Address, Map<Address, Address>>();

    private List<Address> allPairs = new ArrayList<Address>();

    public NulswapFactory(Address _feeToSetter){
        feeToSetter = _feeToSetter;
    }

    public Address createPair(Address tokenA, Address tokenB){
        require(!tokenA.equals(tokenB), "NulswapV3: IDENTICAL_ADDRESSES");

        Address token0, token1;
        if(tokenA.hashCode() < tokenB.hashCode()){
            token0 = tokenA;
            token1 = tokenB;
        }else{
            token0 = tokenB;
            token1 = tokenA;
        }

        require(token0 != null, "NulswapV3: ZERO_ADDRESS");

        Map<Address, Address> ownerAllowed = getPair.get(token0);

        require(getPair.get(token0) == null || getPair.get(token0).get(token1) == null, "NulswapV3: PAIR_EXISTS"); // single check is sufficient

        String pairAddr =  Utils.deploy(new String[]{ "pair", "i"+ BigInteger.valueOf(Block.timestamp()).toString()}, new Address("NULSd6HgjhNpW8kAFMTo2DTqo5P96MXyNtybg"), new String[]{});
        Address pair = new Address(pairAddr);

        initialize(pair, token0, token1);

        Map<Address, Address> tkn0tkn1 = getPair.get(token0) != null ? getPair.get(token0) : new HashMap<>();
        Map<Address, Address> tkn1tkn0 = getPair.get(token1) != null ? getPair.get(token1) : new HashMap<>();

        tkn0tkn1.put(token1, pair);
        tkn1tkn0.put(token0, pair);
        getPair.put(token0, tkn0tkn1);
        getPair.put(token1, tkn1tkn0);

        allPairs.add(pair);
        //emit PairCreated(token0, token1, pair, allPairs.length);
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

    @View
    public Address getFeeTo(){
        return feeTo;
    }

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

}