import io.nuls.contract.sdk.*;
import io.nuls.contract.sdk.annotation.*;
import io.nuls.contract.sdk.event.DebugEvent;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static io.nuls.contract.sdk.Utils.require;


/**
 * @title   Nulswap Router
 *
 * @dev Implements Nulswap general logic, Nulswap Fees, Blacklist and Pause
 *
 * @author  Pedro G. S. Ferreira
 *
 */
public class NulswapRouter extends Ownable implements Contract{

    /** Constants **/
    private static final BigInteger BASIS_POINTS        = BigInteger.valueOf(10000);                            // Math Helper for percentages
    private static final BigInteger MIN_TRANSFERABLE    = BigInteger.valueOf(1000000);                          // Minimum Transferable Amount
    private final Address BURNER_ADDR; // Burn Address
    private final Address wAssetCopy; // wAssetCopy Address

    /** Variables **/
    private Address factory;                                                            // Factory
    private Address WNULS;                                                              // WNULS
    private Address treasury;                                                           // Treasury

    private BigInteger platformFee;                                                     // Platform fee (in basis points)
    private BigInteger refFee;                                                          // Referral fee (in basis points)
    private Boolean paused;                                                             // Pause Status

    private Map<Address, Boolean> blacklist;                                            // Blacklisted Users
    private Map<Integer, Map<Integer, Address>> _wAssets;                               // Store MultiAssets Wrapped Tokens

    /**
     * Constructor
     *
     * @param _factory Factory Address
     * @param _WNULS   WNULS Address
     */
    public NulswapRouter(Address _factory, Address _WNULS, Address _treasury){

        require(_factory != null && _WNULS != null && _treasury != null, "Invalid factory and wnuls");

        factory     = _factory;
        WNULS       = _WNULS;
        treasury    = _treasury;
        _wAssets    = new HashMap<Integer, Map<Integer, Address>>();
        blacklist   = new HashMap<Address, Boolean>();
        platformFee = BigInteger.valueOf(100);          // 1% platform fee
        refFee      = BigInteger.valueOf(50);           // 0.5% referral fee
        paused      = false;
        if (Msg.sender().toString().startsWith("NULS")) {
            BURNER_ADDR = new Address("NULSd6HgsVSzCAJwLYBjvfP3NwbKCvV525GWn");
            wAssetCopy = new Address("tNULSeBaN3wdVVnKDcUm9HWbxtuhWeofkPngjb");//TODO deploy on mainNet
        } else {
            BURNER_ADDR = new Address("tNULSeBaN5nddf9WkQgRr3RNwARgryndv2Bzs6");
            wAssetCopy = new Address("tNULSeBaN3wdVVnKDcUm9HWbxtuhWeofkPngjb");
        }
    }

    /**
     * Ensure tx is done before deadline
     *
     * @param deadline The timestamp when the tx is not valid anymore
     */
    protected void ensure(BigInteger deadline) {
        require(deadline.compareTo(BigInteger.valueOf(Block.timestamp())) >= 0, "NulswapV3: Expired order");
    }

    /**
     * Ensure Msg.sender() is not blacklisted
     */
    protected void blacklist() {
        require(blacklist.get(Msg.sender()) == null || !blacklist.get(Msg.sender()), "NulswapV3: Blacklisted");
    }


    /**
     * Ensure that router is not paused
     */
    protected void whenNotPaused() {
        require(!paused, "NulswapV3: Paused");
    }

    /**
     * Fallback function in case someone transfer nuls to the contract
     */
    @Payable
    @Override
    public void _payable() {
        require(Msg.sender().equals(WNULS), "NulswapV3: Only WNULS can call payable"); // only accept NULS via fallback from the WETH contract
    }

    /**
     * Fallback function in case someone transfer nuls to the contract
     */
    @PayableMultyAsset
    @Override
    public void _payableMultyAsset() {

        //Only send one MultiAsset
        require(Msg.multyAssetValues().length == 1, "NulswapV3: Send the MultiAsset required or don't send more than one");

        //Retrieve MultiAsset and get the only one sent
        MultyAssetValue[] arAssets  = Msg.multyAssetValues();
        MultyAssetValue mToken1     = arAssets[0];

        //Get the value, chain id and asset id of the Multiasset sent
        BigInteger v = mToken1.getValue();
        int c = mToken1.getAssetChainId();
        int a = mToken1.getAssetId();

        require(Msg.sender().equals(_wAssets.get(c).get(a)), "NulswapV3: Only WAsset can call payable"); // only accept NULS via fallback from the WETH contract

    }

    /**
     *  **** ADD LIQUIDITY ****
     *
     * @param tokenA Token Address
     * @param tokenB Token Address
     * @param amountADesired Amount A
     * @param amountBDesired
     * @param amountAMin
     * @param amountBMin
     */
    private String _addLiquidity(
            Address tokenA,
            Address tokenB,
            BigInteger amountADesired,
            BigInteger amountBDesired,
            BigInteger amountAMin,
            BigInteger amountBMin
    ){


        // create the pair if it doesn't exist yet
        if (safeGetPair(tokenA, tokenB).equals(BURNER_ADDR)) {
            _createPair(tokenA, tokenB);
        }

        // Get current pair reserves
        String resValues    = getReserves(tokenA, tokenB);
        String[] arrOfStr   = resValues.split(",", 2);
        BigInteger reserveA = new BigInteger(arrOfStr[0]);
        BigInteger reserveB = new BigInteger(arrOfStr[1]);

        //
        BigInteger amountA, amountB;
        if (reserveA.compareTo(BigInteger.ZERO) == 0 && reserveB.compareTo(BigInteger.ZERO) == 0) {

            amountA = amountADesired;
            amountB = amountBDesired;

        } else {

            BigInteger amountBOptimal = quote(amountADesired, reserveA, reserveB);

            if (amountBOptimal.compareTo(amountBDesired) <= 0) {
                require(amountBOptimal.compareTo(amountBMin) >= 0, "NulswapV3: INSUFFICIENT_B_AMOUNT");
                amountA = amountADesired;
                amountB = amountBOptimal;
            } else {
                BigInteger amountAOptimal = quote(amountBDesired, reserveB, reserveA);
                require(amountAOptimal.compareTo(amountADesired) <= 0, "AmountAoptimal error");
                require(amountAOptimal.compareTo(amountAMin)     >= 0, "NulswapV3: INSUFFICIENT_A_AMOUNT");
                amountA = amountAOptimal;
                amountB = amountBDesired;
            }

        }

        return amountA + "," + amountB;
    }

    /**
     *  **** ADD LIQUIDITY ****
     *
     * @param tokenA Token Address
     * @param tokenB Token Address
     * @param amountADesired Amount A
     * @param amountBDesired
     * @param amountAMin
     * @param amountBMin
     */
    public String addLiquidity(
            Address tokenA,
            Address tokenB,
            BigInteger amountADesired,
            BigInteger amountBDesired,
            BigInteger amountAMin,
            BigInteger amountBMin,
            Address to,
            BigInteger deadline
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        String addLiqRes        = _addLiquidity(tokenA, tokenB, amountADesired, amountBDesired, amountAMin, amountBMin);

        String[] arrOfStr       = addLiqRes.split(",", 2);
        BigInteger amountA      = new BigInteger(arrOfStr[0]);
        BigInteger amountB      = new BigInteger(arrOfStr[1]);

        Address pair            = safeGetPair(tokenA, tokenB);

        safeTransferFrom(tokenA, Msg.sender(), pair, amountA);
        safeTransferFrom(tokenB, Msg.sender(), pair, amountB);

        BigInteger liquidity    = safeMint(pair, to);

        return amountA + "," + amountB  + "," + liquidity;
    }

    /**
     *  **** ADD LIQUIDITY WITH NULS ****
     *
     * @param token Token Address
     * @param amountTokenDesired Amount A
     * @param amountTokenMin
     * @param amountETHMin
     * @param to
     * @param deadline
     */
    @Payable
    public String addLiquidityNuls(
            Address token,
            BigInteger amountTokenDesired,
            BigInteger amountTokenMin,
            BigInteger amountETHMin,
            Address to,
            BigInteger deadline
    ) {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        // Add Liquidity Logic
        String addLiqRes = _addLiquidity(
                token,
                WNULS,
                amountTokenDesired,
                Msg.value(),
                amountTokenMin,
                amountETHMin
        );

        String[] arrOfStr       = addLiqRes.split(",", 2);
        BigInteger amountToken  = new BigInteger(arrOfStr[0]);
        BigInteger amountETH    = new BigInteger(arrOfStr[1]);

        // Get Pair
        Address pair = safeGetPair(token, WNULS);

        // Transfer essential assets to give liquidity
        safeTransferFrom(token, Msg.sender(), pair, amountToken);
        depositNuls(amountETH);
        safeTransfer(WNULS, pair, amountETH);

        // Mint liquidity to address
        BigInteger liquidity =  safeMint(pair, to); // IUniswapV2Pair(pair).mint(to);

        // If amount left from adding liquidity is enough refund amount
        if (Msg.value().compareTo(amountETH.add(MIN_TRANSFERABLE)) > 0)
            safeTransferETH(Msg.sender(), Msg.value().subtract(amountETH)); // refund dust nuls, if any

        return amountToken + "," + amountETH + "," + liquidity;
    }


    /**
     *  **** ADD LIQUIDITY WITH NULS ****
     *
     * @param token Token Address
     * @param amountTokenDesired Amount A
     * @param amounttokenMin
     * @param amountETHMin
     * @param to
     * @param deadline
     */
    @Payable
    @PayableMultyAsset
    public String addLiquidityWAsset(
            Integer chainId,
            Integer assetId,
            Address token,
            BigInteger amountTokenDesired,
            BigInteger amountTokenMin,
            BigInteger amountETHMin,
            Address to,
            BigInteger deadline
    ) {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(Msg.multyAssetValues().length == 1, "NulswapV3: Send the MultiAsset required or don't send more than one");

        MultyAssetValue[] arAssets = Msg.multyAssetValues();
        MultyAssetValue mToken1 = arAssets[0];

        int asset = mToken1.getAssetId();
        int chain = mToken1.getAssetChainId();
        BigInteger val = mToken1.getValue();

        require(chainId == chain && assetId == asset, "NulswapV3: Assets deposited does not match");

        String addLiqRes = _addLiquidity(
                token,
                getMUltiwAsset(chainId, assetId),
                amountTokenDesired,
                val,
                amountTokenMin,
                amountETHMin
        );

        String[] arrOfStr       = addLiqRes.split(",", 2);
        BigInteger amountToken  = new BigInteger(arrOfStr[0]);
        BigInteger amountETH    = new BigInteger(arrOfStr[1]);

        Address pair = safeGetPair(token, _wAssets.get(chainId).get(assetId));

        safeTransferFrom(token, Msg.sender(), pair, amountToken);
        depositMultiAsset(amountETH, chainId, assetId, 0);
        safeTransfer(_wAssets.get(chainId).get(assetId), pair, amountETH);

        BigInteger liquidity =  safeMint(pair, to); // IUniswapV2Pair(pair).mint(to);

        if (val.compareTo(amountETH.add(MIN_TRANSFERABLE)) > 0)
            safeTransferWAsset(Msg.sender(), val.subtract(amountETH), chainId, assetId); // refund dust eth, if any

        return amountToken + "," + amountETH + "," + liquidity;
    }

    /**
     *  **** ADD LIQUIDITY WITH NULS ****
     *
     * @param token Token Address
     * @param amountTokenDesired Amount A
     * @param amounttokenMin
     * @param amountETHMin
     * @param to
     * @param deadline
     */
    @Payable
    @PayableMultyAsset
    public String addLiquidityNULSWAsset(
            Integer chainId,
            Integer assetId,
            BigInteger amountWnulsMin,
            BigInteger amountWAssetMin,
            Address to,
            BigInteger deadline
    ) {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(Msg.multyAssetValues().length == 1, "NulswapV3: Send the MultiAsset required or don't send more than one");

        MultyAssetValue[] arAssets = Msg.multyAssetValues();
        MultyAssetValue mToken1 = arAssets[0];

        int asset = mToken1.getAssetId();
        int chain = mToken1.getAssetChainId();
        BigInteger val = mToken1.getValue();

        require(chainId == chain && assetId == asset, "NulswapV3: Assets deposited does not match");

        String addLiqRes = _addLiquidity(
                WNULS,
                getMUltiwAsset(chainId, assetId),
                Msg.value(),
                val,
                amountWnulsMin,
                amountWAssetMin
        );

        String[] arrOfStr       = addLiqRes.split(",", 2);
        BigInteger amountToken  = new BigInteger(arrOfStr[0]);
        BigInteger amountWasset = new BigInteger(arrOfStr[1]);

        Address pair = safeGetPair(WNULS, _wAssets.get(chainId).get(assetId));

        depositNuls(Msg.value());
        depositMultiAsset(amountWasset, chainId, assetId, 0);
        safeTransfer(WNULS, pair, amountToken);
        safeTransfer(_wAssets.get(chainId).get(assetId), pair, amountWasset);

        BigInteger liquidity =  safeMint(pair, to); // IUniswapV2Pair(pair).mint(to);

        if (val.compareTo(amountWasset.add(MIN_TRANSFERABLE)) > 0)
            safeTransferWAsset(Msg.sender(), val.subtract(amountWasset), chainId, assetId); // refund dust eth, if any

        return amountToken + "," + amountWasset + "," + liquidity;
    }

    /**
     *  **** ADD LIQUIDITY WITH NULS ****
     *
     * @param token Token Address
     * @param amountTokenDesired Amount A
     * @param amounttokenMin
     * @param amountETHMin
     * @param to
     * @param deadline
     */
    @Payable
    @PayableMultyAsset
    public String addLiquidityWAssetAndWAsset(
            Integer chainId,
            Integer assetId,
            Integer chainId2,
            Integer assetId2,
            BigInteger amountTokenMin,
            BigInteger amountETHMin,
            Address to,
            BigInteger deadline
    ) {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(Msg.multyAssetValues().length == 2, "NulswapV3: Send the MultiAsset required or don't send more than one");

        MultyAssetValue[] arAssets = Msg.multyAssetValues();
        MultyAssetValue mToken1 = arAssets[0];

        int asset = mToken1.getAssetId();
        int chain = mToken1.getAssetChainId();
        BigInteger val = mToken1.getValue();

        MultyAssetValue mToken2 = arAssets[1];
        int asset2 = mToken2.getAssetId();
        int chain2 = mToken2.getAssetChainId();
        BigInteger val2 = mToken2.getValue();

        require(chainId == chain && assetId == asset, "NulswapV3: Amount deposited does not match");
        require(chainId2 == chain2 && assetId2 == asset2, "NulswapV3: Amount deposited does not match");

        String addLiqRes = _addLiquidity(
                getMUltiwAsset(chainId, assetId),
                getMUltiwAsset(chainId2, assetId2),
                val,
                val2,
                amountTokenMin,
                amountETHMin
        );

        String[] arrOfStr       = addLiqRes.split(",", 2);
        BigInteger amountToken  = new BigInteger(arrOfStr[0]);
        BigInteger amountETH    = new BigInteger(arrOfStr[1]);

        Address pair = safeGetPair(_wAssets.get(chainId).get(assetId), _wAssets.get(chainId2).get(assetId2));

        depositMultiAsset(amountToken, chainId, assetId, 0);
        depositMultiAsset(amountETH, chainId2, assetId2, 1);

        safeTransfer(_wAssets.get(chainId).get(assetId), pair, amountToken);
        safeTransfer(_wAssets.get(chainId2).get(assetId2), pair, amountETH);

        BigInteger liquidity =  safeMint(pair, to); // IUniswapV2Pair(pair).mint(to);

        if (val2.compareTo(amountETH.add(MIN_TRANSFERABLE)) > 0)
            safeTransferWAsset(Msg.sender(), val2.subtract(amountETH), chainId2, assetId2); // refund dust eth, if any

        return amountToken + "," + amountETH + "," + liquidity;
    }

    /**
     * **** REMOVE LIQUIDITY ****
     *
     *
     * @param tokenA
     */
    public String removeLiquidity(
            Address tokenA,
            Address tokenB,
            BigInteger liquidity,
            BigInteger amountAMin,
            BigInteger amountBMin,
            Address to,
            BigInteger deadline
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        Address pair = safeGetPair(tokenA, tokenB);
        safeTransferFrom(safeGetLP(pair), Msg.sender(), pair, liquidity);

        String[] arrOfStr  = safeBurn(pair, to).split(",", 2);
        BigInteger amount0 = new BigInteger(arrOfStr[0]);
        BigInteger amount1 = new BigInteger(arrOfStr[1]);

        String[] arrOfStr2 = sortTokens(tokenA, tokenB).split(",", 2);
        Address token0     = new Address(arrOfStr2[0]);

        BigInteger amountA, amountB;
        if(tokenA.equals(token0)) {
            amountA = amount0;
            amountB = amount1;
        }else{
            amountA = amount1;
            amountB = amount0;
        }

        require(amountA.compareTo(amountAMin) >= 0, "NulswapV3: INSUFFICIENT_A_AMOUNT");
        require(amountB.compareTo(amountBMin) >= 0, "NulswapV3: INSUFFICIENT_B_AMOUNT");

        return amountA + "," + amountB;
    }

    /**
     *
     * Remove Liquidity in Token and Nuls
     *
     * @param token Token Contract Address
     * @param liquidity
     * @param amountTokenMin
     * @param amountETHMin
     * @param to
     * @param deadline
     * */
    public String removeLiquidityNuls(
            Address token,
            BigInteger liquidity,
            BigInteger amountTokenMin,
            BigInteger amountETHMin,
            Address to,
            BigInteger deadline
    ) {
        ensure(deadline);
        blacklist();
        whenNotPaused();

       String resVal = removeLiquidity(
                token,
                WNULS,
                liquidity,
                amountTokenMin,
                amountETHMin,
                Msg.address(),
                deadline
        );
        String[] arrOfStr       = resVal.split(",", 2);
        BigInteger amountToken  = new BigInteger(arrOfStr[0]);
        BigInteger amountETH    = new BigInteger(arrOfStr[1]);

        safeTransfer(token, to, amountToken);
        withdrawNuls(amountETH);

        safeTransferETH(to, amountETH);

        return amountToken + "," + amountETH;
    }

    /**
     *  Remove Liquidity and MultiAsset
     *
     * @param token Token Contract Address
     * @param liquidity
     * @param amountTokenMin
     * @param amountETHMin
     * @param to
     * @param deadline
     * */
    public String removeLiquidityNulsWAsset(
            Integer chainId,
            Integer assetId,
            BigInteger liquidity,
            BigInteger amountTokenMin,
            BigInteger amountETHMin,
            Address to,
            BigInteger deadline
    ) {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        String resVal = removeLiquidity(
                WNULS,
                _wAssets.get(chainId).get(assetId),
                liquidity,
                amountTokenMin,
                amountETHMin,
                Msg.address(),
                deadline
        );
        String[] arrOfStr       = resVal.split(",", 2);
        BigInteger amountToken  = new BigInteger(arrOfStr[0]);
        BigInteger amountETH    = new BigInteger(arrOfStr[1]);


        withdrawNuls(amountToken);

        safeTransferETH(to, amountToken);

        withdrawWAsset(_wAssets.get(chainId).get(assetId), amountETH);

        safeTransferWAsset(to, amountETH, chainId, assetId);

        return amountToken + "," + amountETH;
    }

    /**
     *
     * @param token
     * @param liquidity
     * @param amountTokenMin
     * @param amountETHMin
     * @param to
     * @param deadline
     * */
    public String removeLiquidityWAsset(
            Integer chainId,
            Integer assetId,
            Address token,
            BigInteger liquidity,
            BigInteger amountTokenMin,
            BigInteger amountETHMin,
            Address to,
            BigInteger deadline
    ) {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        String resVal = removeLiquidity(
                token,
                _wAssets.get(chainId).get(assetId),
                liquidity,
                amountTokenMin,
                amountETHMin,
                Msg.address(),
                deadline
        );
        String[] arrOfStr       = resVal.split(",", 2);
        BigInteger amountToken  = new BigInteger(arrOfStr[0]);
        BigInteger amountETH    = new BigInteger(arrOfStr[1]);

        safeTransfer(token, to, amountToken);
        withdrawWAsset(_wAssets.get(chainId).get(assetId), amountETH);

        safeTransferWAsset(to, amountETH, chainId, assetId);

        return amountToken + "," + amountETH;
    }

    /**
     *
     * @param token
     * @param liquidity
     * @param amountTokenMin
     * @param amountETHMin
     * @param to
     * @param deadline
     * */
    public String removeLiquidityWAssetWAsset(
            Integer chainId,
            Integer assetId,
            Integer chainId2,
            Integer assetId2,
            BigInteger liquidity,
            BigInteger amountTokenMin,
            BigInteger amountETHMin,
            Address to,
            BigInteger deadline
    ) {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        String resVal = removeLiquidity(
                _wAssets.get(chainId).get(assetId),
                _wAssets.get(chainId2).get(assetId2),
                liquidity,
                amountTokenMin,
                amountETHMin,
                Msg.address(),
                deadline
        );
        String[] arrOfStr       = resVal.split(",", 2);
        BigInteger amountToken  = new BigInteger(arrOfStr[0]);
        BigInteger amountETH    = new BigInteger(arrOfStr[1]);


        withdrawWAsset(_wAssets.get(chainId).get(assetId), amountToken);

        safeTransferWAsset(to, amountToken, chainId, assetId);

        withdrawWAsset(_wAssets.get(chainId2).get(assetId2), amountETH);

        safeTransferWAsset(to, amountETH, chainId2, assetId2);

        return amountToken + "," + amountETH;
    }

    /**
     * Take Fee from trade
     *
     * */
    private BigInteger takeFee(BigInteger amountIn, Address payingToken, Address ref){

        BigInteger fee = amountIn.multiply(platformFee).divide(BASIS_POINTS);

        BigInteger referralFee = BigInteger.ZERO;
        if(!ref.equals(BURNER_ADDR)){
            referralFee = amountIn.multiply(refFee).divide(BASIS_POINTS);
            if(referralFee.compareTo(BigInteger.ZERO) > 0){
                safeTransferFrom(payingToken, Msg.sender(),  ref, referralFee);
            }
        }

        safeTransferFrom(payingToken, Msg.sender(),treasury, fee.subtract(referralFee));

        amountIn = amountIn.subtract(fee);

        return amountIn;
    }

    private BigInteger takeFeeInternal(BigInteger amountIn, Address payingToken, Address ref){

        BigInteger fee = amountIn.multiply(platformFee).divide(BASIS_POINTS);

        BigInteger referralFee = BigInteger.ZERO;
        if(!ref.equals(BURNER_ADDR)){
            referralFee = amountIn.multiply(refFee).divide(BASIS_POINTS);
            if(referralFee.compareTo(BigInteger.ZERO) > 0){
                safeTransfer(payingToken,  ref, referralFee);
            }
        }

        safeTransfer(payingToken, treasury, fee.subtract(referralFee));

        amountIn = amountIn.subtract(fee);

        return amountIn;
    }

    private BigInteger takeFeeOutput(BigInteger amountIn, Address payingToken, Address ref){

        BigInteger fee = amountIn.multiply(platformFee).divide(BASIS_POINTS);

        BigInteger referralFee = BigInteger.ZERO;
        if(!ref.equals(BURNER_ADDR)){
            referralFee = amountIn.multiply(refFee).divide(BASIS_POINTS);
            if(referralFee.compareTo(BigInteger.ZERO) > 0){
                safeTransferFrom(payingToken, Msg.sender(),  ref, referralFee);
            }
        }

        safeTransferFrom(payingToken, Msg.sender(),treasury, fee.subtract(referralFee));

        return fee;
    }

    // **** SWAP ****
    // requires the initial amount to have already been sent to the first pair
    private void _swap(String[]  amounts, String[] path, Address _to){

        for (int i = 0; i < path.length - 1; i++) {


            Address input  = new Address(path[i]);
            Address output = new Address(path[i + 1]);

            String[] arrOfStr2      = sortTokens(input, output).split(",", 2);
            Address token0          = new Address(arrOfStr2[0]);
            BigInteger amountOut    = new BigInteger(amounts[i + 1]);

            BigInteger amount0Out, amount1Out;
            if(input.equals(token0)){
                amount0Out = BigInteger.ZERO;
                amount1Out = amountOut;
            }else{
                amount0Out = amountOut;
                amount1Out = BigInteger.ZERO;
            }
            Utils.emit(new DebugEvent("test2", "3..3.3"));
            Address to = (i < path.length - 2 ) ? safeGetPair(output, new Address(path[i + 2])) : _to;

           // IUniswapV2Pair(safeGetPair(input, output)).swap(amount0Out, amount1Out, to);
            safeSwap(safeGetPair(input, output), amount0Out, amount1Out, to);
        }

    }

    /** **** SWAP (supporting fee-on-transfer tokens) ****
    / requires the initial amount to have already been sent to the first pair
     */
    private void _swapSupportingFeeOnTransferTokens(String[]  path, Address _to){

        for (int i = 0; i < path.length - 1; i++) {
            Address input  = new Address(path[i]);
            Address output = new Address(path[i + 1]);

            String[] arrOfStr2      = sortTokens(input, output).split(",", 2);
            Address token0          = new Address(arrOfStr2[0]);

            Address pair = safeGetPair(input, output);
            BigInteger amountInput = BigInteger.ZERO;
            BigInteger amountOutput = BigInteger.ZERO;

            String[] arrOfStr3   = getReserves(input, output).split(",", 3);
            BigInteger reserve0  = new BigInteger(arrOfStr3[0]);
            BigInteger reserve1 = new BigInteger(arrOfStr3[1]);

            BigInteger reserveInput, reserveOutput;
            if(input.equals(token0)){
                reserveInput = reserve0;
                reserveOutput = reserve1;
            }else{
                reserveInput = reserve1;
                reserveOutput = reserve0;
            }
            amountInput = safeBalanceOf(input, pair).subtract(reserveInput);
            amountOutput = getAmountOut(amountInput, reserveInput, reserveOutput);

            BigInteger amount0Out, amount1Out;
            if(input.equals(token0)){
                amount0Out = BigInteger.ZERO;
                amount1Out = amountOutput;
            }else{
                amount0Out = amountOutput;
                amount1Out = BigInteger.ZERO;
            }

            Address to = i < path.length - 2 ? safeGetPair(output, new Address(path[i + 2])) : _to;
            safeSwap(pair, amount0Out, amount1Out, to);
        }
    }

    /**
     *
     *
     *
     * */
    public void swapExactTokensForTokensSupportingFeeOnTransferTokens(
            BigInteger amountIn,
            BigInteger amountOutMin,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    )  {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        amountIn = takeFee(amountIn, new Address(path[0]), ref);

        safeTransferFrom(
                new Address(path[0]), Msg.sender(), safeGetPair(new Address(path[0]), new Address(path[1])), amountIn
        );
        BigInteger balanceBefore = safeBalanceOf(new Address(path[path.length - 1]), to);
        _swapSupportingFeeOnTransferTokens(path, to);
        require(
                (safeBalanceOf(new Address(path[path.length - 1]), to).subtract(balanceBefore)).compareTo(amountOutMin) >= 0,
                "NulswapV3: INSUFFICIENT_OUTPUT_AMOUNT"
        );
    }

    @Payable
    public void  swapExactNulsForTokensSupportingFeeOnTransferTokens(
            BigInteger amountOutMin,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    )
    {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[0]).equals(WNULS), "NulswapV3: INVALID_PATH");

        BigInteger amountIn = Msg.value();

        depositNuls(amountIn);

        amountIn = takeFeeInternal(amountIn, WNULS, ref);

        safeTransfer(WNULS, safeGetPair(new Address(path[0]), new Address(path[1])), amountIn);
        BigInteger balanceBefore = safeBalanceOf(new Address(path[path.length - 1]), to);//IERC20(path[path.length - 1]).balanceOf(to);
        _swapSupportingFeeOnTransferTokens(path, to);
        require(
                safeBalanceOf(new Address(path[path.length - 1]), to).subtract(balanceBefore).compareTo(amountOutMin) >= 0,
                "NulswapV3: INSUFFICIENT_OUTPUT_AMOUNT"
        );
    }

    public void swapExactTokensForNulsSupportingFeeOnTransferTokens(
           BigInteger amountIn,
            BigInteger amountOutMin,
            String[]  path,
            Address to,
            BigInteger deadline,
            Address ref
    )
    {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[path.length - 1]).equals(WNULS), "UniswapV2Router: INVALID_PATH");

        amountIn = takeFee(amountIn, new Address(path[0]), ref);
        safeTransferFrom(
                new Address(path[0]), Msg.sender(), safeGetPair( new Address(path[0]), new Address(path[1])), amountIn
        );



        _swapSupportingFeeOnTransferTokens(path, Msg.address());
        BigInteger amountOut = safeBalanceOf(WNULS, Msg.address());// IERC20(WETH).balanceOf(address(this));

        require(amountOut.compareTo(amountOutMin) >= 0, "UniswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT");
        withdrawNuls(amountOut);
        safeTransferETH(to, amountOut);
    }

    @PayableMultyAsset
    public void  swapExactWAssetForTokensSupportingFeeOnTransferTokens(
            Integer chainId,
            Integer assetId,
            BigInteger amountOutMin,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[0]).equals(_wAssets.get(chainId).get(assetId)), "UniswapV2Router: INVALID_PATH");

        require(Msg.multyAssetValues().length == 1, "NulswapV3: Send the MultiAsset required or don't send more than one");

        MultyAssetValue[] arAssets = Msg.multyAssetValues();
        MultyAssetValue mToken1 = arAssets[0];

        int asset = mToken1.getAssetId();
        int chain = mToken1.getAssetChainId();
        BigInteger val = mToken1.getValue();

        require(chainId == chain && assetId == asset, "NulswapV3: Amount deposited does not match");

        BigInteger amountIn = val;
        depositMultiAsset(amountIn, chainId, assetId, 0);

        amountIn = takeFeeInternal(amountIn,_wAssets.get(chainId).get(assetId), ref);

        safeTransfer(_wAssets.get(chainId).get(assetId), safeGetPair(new Address(path[0]), new Address(path[1])), amountIn);
        BigInteger balanceBefore = safeBalanceOf(new Address(path[path.length - 1]), to);//IERC20(path[path.length - 1]).balanceOf(to);
        _swapSupportingFeeOnTransferTokens(path, to);
        require(
                safeBalanceOf(new Address(path[path.length - 1]), to).subtract(balanceBefore).compareTo(amountOutMin) >= 0,
                "UniswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT"
        );
    }

    public void swapExactTokensForWAssetSupportingFeeOnTransferTokens(
            Integer chainId,
            Integer assetId,
            BigInteger amountIn,
            BigInteger amountOutMin,
            String[]  path,
            Address to,
            BigInteger deadline,
            Address ref
    )
    {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[path.length - 1]).equals(_wAssets.get(chainId).get(assetId)), "UniswapV2Router: INVALID_PATH");

        amountIn = takeFee(amountIn, new Address(path[0]), ref);
        safeTransferFrom(
                new Address(path[0]), Msg.sender(), safeGetPair( new Address(path[0]), new Address(path[1])), amountIn
        );



        _swapSupportingFeeOnTransferTokens(path, Msg.address());
        BigInteger amountOut = safeBalanceOf(_wAssets.get(chainId).get(assetId), Msg.address());// IERC20(WETH).balanceOf(address(this));

        require(amountOut.compareTo(amountOutMin) >= 0, "NulswapV3: INSUFFICIENT_OUTPUT_AMOUNT");
        withdrawWAsset(_wAssets.get(chainId).get(assetId), amountOut);
        safeTransferWAsset(to, amountOut, chainId, assetId);
    }

    /**
     * Swap from a Token to a Token
     *
     * @param amountIn Amount from initial token that user wants to swap
     * @param amountOutMin Minimum amount of tokens a user wants to receive from the trade
     * @param path The path of tokens that will be used to reach the best token output possible
     * @param to The address that will receive the result of the trade
     * @param deadline  Until when this trade is valid
     * */
    @JSONSerializable
    public String[] swapExactTokensForTokens(
            BigInteger amountIn,
            BigInteger amountOutMin,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        amountIn = takeFee(amountIn, new Address(path[0]), ref);

        String[] amounts = getAmountsOut(amountIn, path);
        require(new BigInteger(amounts[amounts.length - 1]).compareTo(amountOutMin) >= 0, "NulswapV3: INSUFFICIENT_OUTPUT_AMOUNT");

        safeTransferFrom(new Address(path[0]), Msg.sender(), safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0]));

        _swap(amounts, path, to);

        return amounts;
    }

    /**
     *
     * @param amountOut
     * @param amountInMax
     * @param path Array of tokens
     * @param to Address that will receive the output token
     * @param deadline
     */
    @JSONSerializable
    public String[] swapTokensForExactTokens(
            BigInteger amountOut,
            BigInteger amountInMax,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ) {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        String[] amounts = getAmountsIn(amountOut, path);
        BigInteger fee = takeFeeOutput(new BigInteger(amounts[0]), new Address(path[0]), ref);

        require((new BigInteger(amounts[0]).add(fee)).compareTo(amountInMax) <= 0, "NulswapV2Router: EXCESSIVE_INPUT_AMOUNT");
        safeTransferFrom(new Address(path[0]), Msg.sender(), safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0]));

        _swap(amounts, path, to);
        return amounts;
    }


    /**
     *
     *
     * @param amountOutMin
     * @param path
     * @param to
     * @param deadline
     * */
    @Payable
    @JSONSerializable
   public String[] swapExactNulsForTokens(
           BigInteger amountOutMin,
           String[] path,
           Address to,
           BigInteger deadline,
           Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[0]).equals(WNULS), "NulswapV2Router: INVALID_PATH");

        depositNuls(Msg.value()); //IWETH(WETH).deposit{value: amounts[0]}();
        BigInteger realVal = takeFeeInternal(Msg.value(), new Address(path[0]), ref);

        String[] amounts = getAmountsOut(realVal, path);

        require(new BigInteger(amounts[amounts.length - 1]).compareTo(amountOutMin) >= 0, "NulswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT");

        require(safeTransfer(WNULS, safeGetPair( new Address(path[0]), new Address(path[1])), realVal), "Transfer failed");
        _swap(amounts, path, to);

        return amounts;
    }

    /**
     * Swap tokens for an exact amount of Nuls
     *
     * @param amountOut Output Amount
     * @param amountInMax Maximum input amount desired
     * @param path
     * @param to Receiver Address
     * @param deadline  Deadline date
     * @param ref Referral Address
     * */
    @JSONSerializable
    public String[] swapTokensForExactNuls(
            BigInteger amountOut,
            BigInteger amountInMax,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[path.length - 1]).equals(WNULS), "NulswapV3: INVALID_PATH");
        String[] amounts = getAmountsIn(amountOut, path);
        BigInteger fee = takeFeeOutput(new BigInteger(amounts[0]), new Address(path[0]), ref);

        require((new BigInteger(amounts[0]).add(fee)).compareTo(amountInMax) <= 0, "NulswapV3: EXCESSIVE_INPUT_AMOUNT");
        safeTransferFrom(new Address(path[0]), Msg.sender(), safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0]));
        _swap(amounts, path, Msg.address());
        withdrawNuls(new BigInteger(amounts[amounts.length - 1]));
        safeTransferETH(to, new BigInteger(amounts[amounts.length - 1]));
        return amounts;
    }

    /**
     *
     * @param amountIn
     * @param amountOutMin
     * @param path
     * @param to
     * @param deadline
     */
    @JSONSerializable
    public String[] swapExactTokensForNuls(
            BigInteger amountIn,
            BigInteger amountOutMin,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        amountIn = takeFee(amountIn, new Address(path[0]), ref);

        require(new Address(path[path.length - 1]).equals(WNULS),"NulswapRouterV3: INVALID_PATH");
        String[] amounts = getAmountsOut(amountIn, path);

        require(new BigInteger(amounts[amounts.length - 1]).compareTo(amountOutMin) >= 0, "NulswapRouterV3: INSUFFICIENT_OUTPUT_AMOUNT");
        safeTransferFrom(new Address(path[0]), Msg.sender(), safeGetPair(new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0]));
        _swap(amounts, path, Msg.address());

        withdrawNuls(new BigInteger(amounts[amounts.length - 1])); //IWETH(WETH).withdraw(amounts[amounts.length - 1]);
        safeTransferETH(to, new BigInteger(amounts[amounts.length - 1]));

        return amounts;
    }

    /**
     *
     * @param amountOut
     * @param path
     * @param to
     * @param deadline
     */
    @Payable
    @JSONSerializable
    public String[] swapNulsForExactTokens(
            BigInteger amountOut,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[0]).equals(WNULS), "NulswapV2Router: INVALID_PATH");
        String[] amounts = getAmountsIn(amountOut, path);

        depositNuls(new BigInteger(amounts[0]));
        BigInteger fee = takeFeeOutput(new BigInteger(amounts[0]), new Address(path[0]), ref);
        depositNuls(fee);
        require((new BigInteger(amounts[0]).add(fee)).compareTo(Msg.value()) <= 0, "NulswapV2Router: EXCESSIVE_INPUT_AMOUNT");


        require(safeTransfer(WNULS, safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0])), "Failed Transfer");
        _swap(amounts, path, to);

        if (Msg.value().compareTo(new BigInteger(amounts[0]).add(MIN_TRANSFERABLE)) > 0) safeTransferETH(Msg.sender(), Msg.value().subtract(new BigInteger(amounts[0]))); // refund dust eth, if any
        return amounts;
    }

    /**
     *
     *
     * @param amountOutMin
     * @param path
     * @param to
     * @param deadline
     * */
    @PayableMultyAsset
    @JSONSerializable
    public String[] swapExactWAssetForTokens(
            Integer chainId,
            Integer assetId,
            BigInteger amountOutMin,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(Msg.multyAssetValues().length == 1, "NulswapV3: Send the MultiAsset required or don't send more than one");

        MultyAssetValue[] arAssets = Msg.multyAssetValues();
        MultyAssetValue mToken1 = arAssets[0];

        int asset = mToken1.getAssetId();
        int chain = mToken1.getAssetChainId();
        BigInteger val = mToken1.getValue();

        require(chainId == chain && assetId == asset, "NulswapV1: Amount deposited does not match");

        require(new Address(path[0]).equals(_wAssets.get(chainId).get(assetId)), "NulswapV2Router: INVALID_PATH");
        String[] amounts = getAmountsOut(val, path);
        depositMultiAsset(new BigInteger(amounts[0]), chainId , assetId, 0);
        //todo calc fee?
        BigInteger realVal = takeFee(val, new Address(path[0]), ref);
        require(new BigInteger(amounts[amounts.length - 1]).compareTo(amountOutMin) >= 0, "NulswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT");


        require(safeTransfer(_wAssets.get(chainId).get(assetId), safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0])), "Transfer failed");
        _swap(amounts, path, to);

        return amounts;
    }

    /**
     *
     * @param amountOut
     * @param amountInMax
     * @param path
     * @param to
     * @param deadline
     * */
    @JSONSerializable
    public String[] swapTokensForExactWAsset(
            Integer chainId,
            Integer assetId,
            BigInteger amountOut,
            BigInteger amountInMax,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref)
    {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[path.length - 1]).equals(_wAssets.get(chainId).get(assetId)), "UniswapV2Router: INVALID_PATH");
        String[] amounts = getAmountsIn(amountOut, path);
        BigInteger fee = takeFeeOutput(new BigInteger(amounts[0]), new Address(path[0]), ref);

        require((new BigInteger(amounts[0]).add(fee)).compareTo(amountInMax) <= 0, "UniswapV2Router: EXCESSIVE_INPUT_AMOUNT");
        safeTransferFrom(new Address(path[0]), Msg.sender(), safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0]));
        _swap(amounts, path, Msg.address());
        withdrawWAsset(getMUltiwAsset(chainId, assetId),new BigInteger(amounts[amounts.length - 1]) );
        safeTransferWAsset(to, new BigInteger(amounts[amounts.length - 1]), chainId, assetId);
        return amounts;
    }

    /**
     *
     * @param amountIn
     * @param amountOutMin
     * @param path
     * @param to
     * @param deadline
     */
    @JSONSerializable
    public String[] swapExactTokensForWAsset(
            Integer chainId,
            Integer assetId,
            BigInteger amountIn,
            BigInteger amountOutMin,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        amountIn = takeFee(amountIn, new Address(path[0]), ref);

        require(new Address(path[path.length - 1]).equals(_wAssets.get(chainId).get(assetId)),"NulswapRouterV3: INVALID_PATH");
        String[] amounts = getAmountsOut(amountIn, path);

        require(new BigInteger(amounts[amounts.length - 1]).compareTo(amountOutMin) >= 0, "NulswapRouterV3: INSUFFICIENT_OUTPUT_AMOUNT");
        safeTransferFrom(new Address(path[0]), Msg.sender(), safeGetPair(new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0]));
        _swap(amounts, path, Msg.address());

        withdrawWAsset(getMUltiwAsset(chainId, assetId), new BigInteger(amounts[amounts.length - 1])); //IWETH(WETH).withdraw(amounts[amounts.length - 1]);
        safeTransferWAsset(to, new BigInteger(amounts[amounts.length - 1]), chainId, assetId);

        return amounts;
    }

    /**
     *
     * @param amountOut
     * @param path
     * @param to
     * @param deadline
     */
    @PayableMultyAsset
    @JSONSerializable
    public String[] swapWAssetForExactTokens(
            Integer chainId,
            Integer assetId,
            BigInteger amountOut,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[0]).equals(_wAssets.get(chainId).get(assetId)), "NulswapV3Router: INVALID_PATH");
        String[] amounts = getAmountsIn(amountOut, path);

        require(Msg.multyAssetValues().length == 1, "NulswapV3: Send the MultiAsset required or don't send more than one");

        MultyAssetValue[] arAssets = Msg.multyAssetValues();
        MultyAssetValue mToken1 = arAssets[0];

        int asset = mToken1.getAssetId();
        int chain = mToken1.getAssetChainId();
        BigInteger val = mToken1.getValue();

        require(chainId == chain && assetId == asset, "NulswapV1: Amount deposited does not match");

        require(new BigInteger(amounts[0]).compareTo(val) <= 0, "NulswapV2Router: EXCESSIVE_INPUT_AMOUNT");

        depositMultiAsset(new BigInteger(amounts[0]), chainId, assetId, 0);

        amounts[0] = takeFeeInternal(new BigInteger(amounts[0]), _wAssets.get(chainId).get(assetId), ref).toString();

        require(safeTransfer(_wAssets.get(chainId).get(assetId), safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0])), "Failed Transfer");
        _swap(amounts, path, to);

        if (val.compareTo(new BigInteger(amounts[0]).add(MIN_TRANSFERABLE)) > 0) safeTransferWAsset(Msg.sender(), val.subtract(new BigInteger(amounts[0])), chainId, assetId); // refund dust eth, if any
        return amounts;
    }

    /**
     *
     *
     * @param amountOutMin
     * @param path
     * @param to
     * @param deadline
     * */
    @Payable
    @JSONSerializable
    public String[] swapExactNulsForWAsset(
            Integer chainId,
            Integer assetId,
            BigInteger amountOutMin,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[0]).equals(WNULS), "NulswapV2Router: INVALID_PATH");
        require(new Address(path[path.length - 1]).equals(_wAssets.get(chainId).get(assetId)), "NulswapV2Router: INVALID_PATH");
        String[] amounts = getAmountsOut(Msg.value(), path);

        require(new BigInteger(amounts[amounts.length - 1]).compareTo(amountOutMin) >= 0, "NulswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT");
        depositNuls(new BigInteger(amounts[0])); //IWETH(WETH).deposit{value: amounts[0]}();

        amounts[0] = takeFeeInternal(new BigInteger(amounts[0]) ,WNULS, ref).toString();

        require(safeTransfer(WNULS, safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0])), "Transfer failed");

        _swap(amounts, path, Msg.address());

        withdrawWAsset(getMUltiwAsset(chainId, assetId), new BigInteger(amounts[amounts.length - 1])); //IWETH(WETH).withdraw(amounts[amounts.length - 1]);
        safeTransferWAsset(to, new BigInteger(amounts[amounts.length - 1]), chainId, assetId);


        return amounts;
    }

    /**
     *
     * @param amountOut
     * @param amountInMax
     * @param path
     * @param to
     * @param deadline
     * */
    @PayableMultyAsset
    @JSONSerializable
    public String[] swapWAssetForExactNuls(
            Integer chainId,
            Integer assetId,
            BigInteger amountOut,
            BigInteger amountInMax,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
            )
    {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[0]).equals(_wAssets.get(chainId).get(assetId)), "NulswapV3Router: INVALID_PATH");
        require(new Address(path[path.length - 1]).equals(WNULS), "UniswapV2Router: INVALID_PATH");
        String[] amounts = getAmountsIn(amountOut, path);

        require(Msg.multyAssetValues().length == 1, "NulswapV3: Send the MultiAsset required or don't send more than one");

        MultyAssetValue[] arAssets = Msg.multyAssetValues();
        MultyAssetValue mToken1 = arAssets[0];

        int asset = mToken1.getAssetId();
        int chain = mToken1.getAssetChainId();
        BigInteger val = mToken1.getValue();

        require(chainId == chain && assetId == asset, "NulswapV1: Amount deposited does not match");

        require(new BigInteger(amounts[0]).compareTo(val) <= 0, "NulswapV2Router: EXCESSIVE_INPUT_AMOUNT");

        depositMultiAsset(new BigInteger(amounts[0]), chainId, assetId, 0);

        amounts[0] = takeFeeInternal(new BigInteger(amounts[0]), _wAssets.get(chainId).get(assetId), ref).toString();
        require(safeTransfer(_wAssets.get(chainId).get(assetId), safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0])), "Failed Transfer");
        _swap(amounts, path, Msg.address());
        withdrawNuls(new BigInteger(amounts[amounts.length - 1]));
        safeTransferETH(to, new BigInteger(amounts[amounts.length - 1]));
        return amounts;
    }

    /**
     *
     * @param amountIn
     * @param amountOutMin
     * @param path
     * @param to
     * @param deadline
     */
    @PayableMultyAsset
    @JSONSerializable
    public String[] swapExactWAssetForNuls(
            Integer chainId,
            Integer assetId,
            BigInteger amountIn,
            BigInteger amountOutMin,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(Msg.multyAssetValues().length == 1, "NulswapV3: Send the MultiAsset required or don't send more than one");

        MultyAssetValue[] arAssets = Msg.multyAssetValues();
        MultyAssetValue mToken1 = arAssets[0];

        int asset = mToken1.getAssetId();
        int chain = mToken1.getAssetChainId();
        BigInteger val = mToken1.getValue();

        require(chainId == chain && assetId == asset, "NulswapV1: Amount deposited does not match");

        require(new Address(path[0]).equals(_wAssets.get(chainId).get(assetId)), "NulswapV2Router: INVALID_PATH");
        require(new Address(path[path.length - 1]).equals(WNULS), "UniswapV2Router: INVALID_PATH");
        String[] amounts = getAmountsOut(val, path);

        require(new BigInteger(amounts[amounts.length - 1]).compareTo(amountOutMin) >= 0, "NulswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT");
        depositMultiAsset(new BigInteger(amounts[0]), chainId , assetId, 0);

        amounts[0] = takeFeeInternal(new BigInteger( amounts[0]), _wAssets.get(chainId).get(assetId), ref).toString();

        require(safeTransfer(_wAssets.get(chainId).get(assetId), safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0])), "Transfer failed");
        _swap(amounts, path, Msg.address());

        withdrawNuls(new BigInteger(amounts[amounts.length - 1])); //IWETH(WETH).withdraw(amounts[amounts.length - 1]);
        safeTransferETH(to, new BigInteger(amounts[amounts.length - 1]));

        return amounts;
    }

    /**
     *
     * @param amountOut
     * @param path
     * @param to
     * @param deadline
     */
    @Payable
    @JSONSerializable
    public String[] swapNulsForExactWAsset(
            Integer chainId,
            Integer assetId,
            BigInteger amountOut,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[0]).equals(WNULS), "NulswapV2Router: INVALID_PATH");
        require(new Address(path[path.length - 1]).equals(_wAssets.get(chainId).get(assetId)), "UniswapV2Router: INVALID_PATH");
        String[] amounts = getAmountsIn(amountOut, path);
        require(new BigInteger(amounts[0]).compareTo(Msg.value()) <= 0, "NulswapV2Router: EXCESSIVE_INPUT_AMOUNT");

        depositNuls(new BigInteger(amounts[0]));

        amounts[0] = takeFeeInternal(new BigInteger(amounts[0]), WNULS, ref).toString();

        require(safeTransfer(WNULS, safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0])), "Failed Transfer");
        _swap(amounts, path, Msg.address());

        withdrawWAsset(getMUltiwAsset(chainId, assetId),new BigInteger(amounts[amounts.length - 1]) );
        safeTransferWAsset(to, new BigInteger(amounts[amounts.length - 1]), chainId, assetId);
        return amounts;
    }

    /**
     *
     *
     * @param amountOutMin
     * @param path
     * @param to
     * @param deadline
     * */
    @PayableMultyAsset
    @JSONSerializable
    public String[] swapExactWAssetForWAsset(
            Integer chainId,
            Integer assetId,
            Integer chainId2,
            Integer assetId2,
            BigInteger amountOutMin,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    ){
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(Msg.multyAssetValues().length == 1, "NulswapV3: Send the MultiAsset required or don't send more than one");

        MultyAssetValue[] arAssets = Msg.multyAssetValues();
        MultyAssetValue mToken1 = arAssets[0];

        int asset = mToken1.getAssetId();
        int chain = mToken1.getAssetChainId();
        BigInteger val = mToken1.getValue();

        require(chainId == chain && assetId == asset, "NulswapV1: Amount deposited does not match");

        require(new Address(path[0]).equals(_wAssets.get(chainId).get(assetId)), "NulswapV2Router: INVALID_PATH");
        require(new Address(path[path.length - 1]).equals(_wAssets.get(chainId2).get(assetId2)), "NulswapV2Router: INVALID_PATH");

        depositMultiAsset(val, chainId , assetId, 0);
        val = takeFeeInternal(val, _wAssets.get(chainId).get(assetId), ref);
        String[] amounts = getAmountsOut(val, path);

        require(new BigInteger(amounts[amounts.length - 1]).compareTo(amountOutMin) >= 0, "NulswapV2Router: INSUFFICIENT_OUTPUT_AMOUNT");


        require(safeTransfer(_wAssets.get(chainId).get(assetId), safeGetPair( new Address(path[0]), new Address(path[1])), val), "Transfer failed");
        _swap(amounts, path, Msg.address());

        withdrawWAsset(getMUltiwAsset(chainId2, assetId2), new BigInteger(amounts[amounts.length - 1])); //IWETH(WETH).withdraw(amounts[amounts.length - 1]);
        safeTransferWAsset(to, new BigInteger(amounts[amounts.length - 1]), chainId2, assetId2);

        return amounts;
    }

    /**
     *
     * @param amountOut
     * @param amountInMax
     * @param path
     * @param to
     * @param deadline
     * */
    @PayableMultyAsset
    @JSONSerializable
    public String[] swapWAssetForExactWAsset(
            Integer chainId,
            Integer assetId,
            Integer chainId2,
            Integer assetId2,
            BigInteger amountOut,
            BigInteger amountInMax,
            String[] path,
            Address to,
            BigInteger deadline,
            Address ref
    )
    {
        ensure(deadline);
        blacklist();
        whenNotPaused();

        require(new Address(path[0]).equals(_wAssets.get(chainId).get(assetId)), "NulswapV3Router: INVALID_PATH");
        require(new Address(path[path.length - 1]).equals(_wAssets.get(chainId2).get(assetId2)), "UniswapV2Router: INVALID_PATH");
        String[] amounts = getAmountsIn(amountOut, path);

        require(Msg.multyAssetValues().length == 1, "NulswapV3: Send the MultiAsset required or don't send more than one");

        MultyAssetValue[] arAssets = Msg.multyAssetValues();
        MultyAssetValue mToken1 = arAssets[0];

        int asset = mToken1.getAssetId();
        int chain = mToken1.getAssetChainId();
        BigInteger val = mToken1.getValue();

        require(chainId == chain && assetId == asset, "NulswapV3: Amount deposited does not match");

        require(new BigInteger(amounts[0]).compareTo(val) <= 0, "NulswapV3: EXCESSIVE_INPUT_AMOUNT");

        depositMultiAsset(new BigInteger(amounts[0]), chainId, assetId, 0);

        amounts[0] = takeFeeInternal(new BigInteger(amounts[0]), _wAssets.get(chainId).get(assetId), ref).toString();

        require(safeTransfer(_wAssets.get(chainId).get(assetId), safeGetPair( new Address(path[0]), new Address(path[1])), new BigInteger(amounts[0])), "Failed Transfer");
        _swap(amounts, path, Msg.address());

        withdrawWAsset(getMUltiwAsset(chainId2, assetId2),new BigInteger(amounts[amounts.length - 1]) );
        safeTransferWAsset(to, new BigInteger(amounts[amounts.length - 1]), chainId2, assetId2);
        return amounts;
    }

    /**
     *  Return the token0 and the token1 from a pair of tokens
     *
     * @param tokenA TokenA Contract Address
     * @param tokenB TokenB Contract Address
     * */
    // returns sorted token addresses, used to handle return values from pairs sorted in this order
    private String sortTokens(Address tokenA, Address tokenB){

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
        return token0 + "," + token1;
    }

    /**
     * Return pair reserves
     *
     * @param tokenA
     * @param tokenB
     * */
    // fetches and sorts the reserves for a pair
    private String getReserves(
            Address tokenA,
            Address tokenB
    ){
        String[] arrOfStr2  = sortTokens(tokenA, tokenB).split(",", 2);
        Address token0      = new Address(arrOfStr2[0]);

        String[] arrOfStr3  = safeGetReserves(safeGetPair(tokenA, tokenB)).split(",", 3);
        BigInteger reserve0 = new BigInteger(arrOfStr3[0]);
        BigInteger reserve1 = new BigInteger(arrOfStr3[1]);

        BigInteger reserveA, reserveB;
        if (tokenA.equals(token0)){
            reserveA = reserve0;
            reserveB = reserve1;
        } else {
            reserveA = reserve1;
            reserveB = reserve0;
        }

        return reserveA + "," + reserveB;
    }


    /**
     * Quote the return output amount given amountA and reserves
     *
     * @param amountA
     * @param reserveA
     * @param reserveB
     * */
    @View
    public BigInteger quote(
            BigInteger amountA,
            BigInteger reserveA,
            BigInteger reserveB
    ){
        require(amountA.compareTo(BigInteger.ZERO) > 0, "NulswapV3: INSUFFICIENT_AMOUNT");
        require(reserveA.compareTo(BigInteger.ZERO) > 0 && reserveB.compareTo(BigInteger.ZERO) > 0, "NulswapV3: INSUFFICIENT_LIQUIDITY");
        BigInteger amountB = amountA.multiply(reserveB).divide(reserveA);
        return amountB;
    }

    /**
     *  Return the output amount
     *
     * @param amountIn
     * @param reserveIn
     * @param reserveOut
     * */
    @View
    public BigInteger getAmountOut(
            BigInteger amountIn,
            BigInteger reserveIn,
            BigInteger reserveOut
    ){

        require(amountIn.compareTo(BigInteger.ZERO) > 0,"NulswapV3: INSUFFICIENT_INPUT_AMOUNT");
        require(reserveIn.compareTo(BigInteger.ZERO) > 0 && reserveOut.compareTo(BigInteger.ZERO) > 0, "NulswapV3: INSUFFICIENT_LIQUIDITY");

        BigInteger amountInWithFee = amountIn.multiply(BigInteger.valueOf(997));
        BigInteger numerator = amountInWithFee.multiply(reserveOut);
        BigInteger denominator = reserveIn.multiply(BigInteger.valueOf(1000)).add(amountInWithFee);
        BigInteger amountOut = numerator.divide(denominator);

        return amountOut;
    }

    /**
     *
     *
     * @param amountOut
     * @param reserveIn
     * @param reserveOut
     * */
    @View
    public BigInteger getAmountIn(
            BigInteger amountOut,
            BigInteger reserveIn,
            BigInteger reserveOut
    ){

        require(amountOut.compareTo(BigInteger.ZERO) > 0, "NulswapRouterV3: INSUFFICIENT_OUTPUT_AMOUNT");
        require(reserveIn.compareTo(BigInteger.ZERO) > 0 && reserveOut.compareTo(BigInteger.ZERO) > 0, "NulswapRouterV3: INSUFFICIENT_LIQUIDITY");

        BigInteger numerator = reserveIn.multiply(amountOut).multiply(BigInteger.valueOf(1000));
        BigInteger denominator = (reserveOut.subtract(amountOut)).multiply(BigInteger.valueOf(997));
        BigInteger amountIn = (numerator.divide(denominator)).add(BigInteger.ONE);

        return amountIn;
    }

    /**
     *
     * @param amountIn
     * @param path
     * */
    @View
    @JSONSerializable
    public String[] getAmountsOut(BigInteger amountIn, String[] path){

        require(path.length >= 2, "NulswapV3: INVALID_PATH");
        String[] amounts = new String[path.length];
        amounts[0]       = amountIn.toString();

        for (int i = 0; i < path.length - 1; i++) {

            String[] arrOfStr3    = getReserves(new Address(path[i]), new Address(path[i + 1])).split(",", 3);
            BigInteger reserveIn  = new BigInteger(arrOfStr3[0]);
            BigInteger reserveOut = new BigInteger(arrOfStr3[1]);
            Utils.emit(new DebugEvent("test2", "2.1"));

            amounts[i + 1] = getAmountOut( new BigInteger(amounts[i]), reserveIn, reserveOut).toString();
        }
        Utils.emit(new DebugEvent("test2", "2.3"));
        return amounts;
    }

    /**
     *
     * @param amountOut
     * @param path
     * */
    @View
    @JSONSerializable
    public String[] getAmountsIn(BigInteger amountOut, String[] path) {

        require(path.length >= 2, "NulswapV3: INVALID_PATH");
        String[] amounts            = new String[path.length];
        amounts[amounts.length - 1] = amountOut.toString();

        Utils.emit(new DebugEvent("test2", "1.1"));

        for (int i = path.length - 1; i > 0; i--) {

            String[] arrOfStr3    = getReserves( new Address(path[i - 1]), new Address(path[i])).split(",", 3);
            BigInteger reserveIn  = new BigInteger(arrOfStr3[0]);
            BigInteger reserveOut = new BigInteger(arrOfStr3[1]);

            Utils.emit(new DebugEvent("test2", "1.2"));
            amounts[i - 1] = getAmountIn(new BigInteger(amounts[i]), reserveIn, reserveOut).toString();
        }
        Utils.emit(new DebugEvent("test2", "1.3"));
        return amounts;
    }

    @View
    public Address getFactory() {
        return factory;
    }

    @View
    public Address getWNULS() {
        return WNULS;
    }

    @View
    public Address getTreasury() {
        return treasury;
    }

    @View
    public BigInteger getPlatformFee() {
        return platformFee;
    }

    @View
    public BigInteger getRefFee() {
        return refFee;
    }

    @View
    public Boolean getPaused() {
        return paused;
    }

    @View
    public boolean getBlacklist(Address address) {
        Boolean b = blacklist.get(address);
        if (b == null) {
            return false;
        }
        return b;
    }

    @View
    public String getWAsset(int chainId, int assetId) {
        Map<Integer, Address> map = _wAssets.get(chainId);
        if (map == null) {
            return "";
        }
        Address address = map.get(assetId);
        if (address == null) {
            return "";
        }
        return address.toString();
    }

    /**
     *
     * @param token
     * @param account
     * */
    private BigInteger safeBalanceOf(@Required Address token, @Required Address account){
        String[][] argsM = new String[][]{new String[]{account.toString()}};
        return new BigInteger(token.callWithReturnValue("balanceOf", "", argsM, BigInteger.ZERO));

    }

    /**
     *
     * @param pair
     * @param amount0Out
     * @param amount1Out
     * @param to
     * */
    private void safeSwap(
            @Required Address pair,
            BigInteger amount0Out,
            BigInteger amount1Out,
            @Required Address to
    ){
        String[][] argsM = new String[][]{new String[]{amount0Out.toString()}, new String[]{amount1Out.toString()}, new String[]{to.toString()}};
        pair.callWithReturnValue("swap", "", argsM, BigInteger.ZERO);
    }

    @View
    public String safePairInfo(@Required Address tokenA, @Required Address tokenB){
        String[] arrOfStr2      = sortTokens(tokenA, tokenB).split(",", 2);
        Address token0          = new Address(arrOfStr2[0]);
        return safeGetPair(tokenA, tokenB)+","+safeGetReserves(safeGetPair(tokenA, tokenB))+","+token0;
    }

    /**
     *
     * @param pair
     */
    private String safeGetReserves(@Required Address pair){
        String[][] argsM = new String[][]{};
        return pair.callWithReturnValue("getReserves", "", argsM, BigInteger.ZERO);
    }

    /**
     *
     * @param pair
     * @param to
     */
    private BigInteger safeMint(@Required Address pair, @Required Address to){
        String[][] argsM = new String[][]{new String[]{to.toString()}};
        return new BigInteger(pair.callWithReturnValue("mint", "", argsM, BigInteger.ZERO));
    }

    /**
     *
     * @param pair
     * @param to
     */
    private String safeBurn(@Required Address pair, @Required Address to){
        String[][] argsM = new String[][]{new String[]{to.toString()}};
        return pair.callWithReturnValue("burn", "", argsM, BigInteger.ZERO);
    }


    /**
     *
     * @param token
     * */
    private BigInteger safeTotalSupply(@Required Address token){
        String[][] argsM = new String[][]{};
        return new BigInteger(token.callWithReturnValue("totalSupply", "", argsM, BigInteger.ZERO));
    }

    /**
     *
     * @param tokenA
     * @param tokenB
     * */
    private Address safeGetPair(@Required Address tokenA, @Required Address tokenB){
        String[][] argsM = new String[][]{new String[]{tokenA.toString()}, new String[]{tokenB.toString()}};
        return new Address(factory.callWithReturnValue("getPair", "", argsM, BigInteger.ZERO));
    }

    /**
     *
     * @param tokenA
     * @param tokenB
     * */
    private Address safeGetLP(@Required Address pair){
        String[][] argsM = new String[][]{};
        return new Address(pair.callWithReturnValue("getLP", "", argsM, BigInteger.ZERO));
    }

    /**
     *  Transfer token from msg.sender to the recipient address
     *
     * @param token Token Address
     * @param recipient Recipient Address
     * @param amount Amount to Transfer
     */
    private Boolean safeTransfer(
            @Required Address token,
            @Required Address recipient,
            @Required BigInteger amount
    ){
        String[][] argsM = new String[][]{new String[]{recipient.toString()}, new String[]{amount.toString()}};
        boolean b = new Boolean(token.callWithReturnValue("transfer", "", argsM, BigInteger.ZERO));
        require(b, "NulswapRouter: Failed to transfer");
        return b;
    }

    /**
     *
     * Safe Transfer Nuls
     *
     * @param recipient
     * @param amount
     * */
    private void safeTransferETH(
            @Required Address recipient,
            @Required BigInteger amount
    ){
            recipient.transfer(amount);
    }

    /**
     *
     * Safe Transfer
     *
     * @param recipient
     * @param amount
     * */
    private void safeTransferWAsset(
            @Required Address recipient,
            @Required BigInteger amount,
            @Required Integer chain,
            @Required Integer asset
    ){
        recipient.transfer(amount,chain, asset);
    }

    /**
     *  Transfer token from the address from to the recipient address
     *
     * @param token Token Address
     * @param from The Address from where the tokens will be retrieved
     * @param recipient Recipient Address
     * @param amount Amount to Transfer
     */
    private void safeTransferFrom(
            @Required Address token,
            @Required Address from,
            @Required Address recipient,
            @Required BigInteger amount
    ){
        String[][] args = new String[][]{new String[]{from.toString()}, new String[]{recipient.toString()}, new String[]{amount.toString()}};
        boolean b = new Boolean(token.callWithReturnValue("transferFrom", "", args, BigInteger.ZERO));
        require(b, "NulswapRouter: Failed to transfer");
    }


    /**
     *
     * Create Pair
     *
     * @param token0
     * @param token1
     * */
    private Address _createPair(Address token0, Address token1){

        String[][] args = new String[][]{ new String[]{token0.toString()}, new String[]{token1.toString()}};
        Address b = new Address(factory.callWithReturnValue("createPair", "", args, BigInteger.ZERO));
        return b;

    }

    /**
     * Deposit nuls and get wnuls in return
     *
     *
     * */
    private void depositNuls(@Required BigInteger v) {

        //Require that the amount sent is equal to the amount requested - Do not remove this verification
        require(Msg.value().compareTo(v) >= 0, "NulswapV1: Value does not match the amount sent");

        //Create arguments and call the deposit function
        String[][] args = new String[][]{new String[]{v.toString()}};
        String rDeposit = WNULS.callWithReturnValue("deposit", null, args, v);

        //require that the deposit was successful
        require(new Boolean(rDeposit), "NulswapV1: Deposit did not succeed");
        //emit(new TokenPurchase(wNull, v, v));
    }

    /**
     * Withdraw nuls from the wnuls contract - must be always private - when I say always is ALWAYS
     *
     * @param v Amount of WNULS to convert to NULS
     */
    private void withdrawNuls(@Required BigInteger v) {
        require(v.compareTo(MIN_TRANSFERABLE) >= 0, "Min nuls transfer not met");

        /*create approve to transfer tokens to the wnuls contract*/
        String[][] argsApprove = new String[][]{new String[]{WNULS.toString()}, new String[]{v.toString()}};
        String rApprove = WNULS.callWithReturnValue("approve", null, argsApprove, BigInteger.ZERO);

        require(new Boolean(rApprove), "NulswapRouter: Approve did not succeeded!");

        //Create arguments and call the withdraw function
        String[][] args = new String[][]{new String[]{v.toString()}, new String[]{Msg.sender().toString()}};
        String rWithdraw = WNULS.callWithReturnValue("withdraw", "", args, BigInteger.ZERO);

        //Require that the withdraw was successful
        require(new Boolean(rWithdraw), "NulswapRouter: Withdraw did not succeed!");
        //emit(new TokenPurchase(WNULS, v, v));
    }

    // Deposit multiasset and get wAsset
    private void depositMultiAsset(@Required BigInteger value, @Required int chainA, @Required int assetA, int idx) {

        //Require that the contract does not sends
        require(!Msg.sender().equals(Msg.address()));

        //Require that the contract does not sends
        require(Msg.multyAssetValues().length == 1 || Msg.multyAssetValues().length == 2 , "NulswapV3: Send the MultiAsset required or don't send more than one");

        MultyAssetValue[] arAssets = Msg.multyAssetValues();
        MultyAssetValue mToken1 = arAssets[idx];

        int assetId = mToken1.getAssetId();
        int chain = mToken1.getAssetChainId();
        BigInteger v = mToken1.getValue();

        //Utils.emit(new DebugEvent("clinitTest log3", assetId + ", "+ chain + ", "+ v + ", "+ value));

        require(value.compareTo(v) == 0 && chainA == chain && assetA == assetId, "NulswapV1: Amount deposited does not match");

        Address routerAsset = getMUltiwAsset(chain, assetId);
        //Utils.emit(new DebugEvent("clinitTest log4", assetId + ", "+ chain + ", "+ v + ", "+ value));

        require(routerAsset != null, "NulswapV1: routerAsset is not yet in the router contract");

        MultyAssetValue vi = new MultyAssetValue(value,chain, assetId);
        MultyAssetValue[] vii = new MultyAssetValue[1];
        vii[0] = vi;
        //Utils.emit(new DebugEvent("clinitTest log5", assetId + ", "+ chain + ", "+ v + ", "+ value));

        String[][] args = new String[][]{new String[]{v.toString()}};
        String rDeposit = routerAsset.callWithReturnValue("deposit", "", args, BigInteger.ZERO, vii);

        require(new Boolean(rDeposit), "Deposit did not succeed");
    }

    /**
     * Withdraw asset from the asset contract - must be always private
     *
     * */
    private void withdrawWAsset(@Required Address wAsset, @Required BigInteger v) {

        /*create approve to transfer tokens to the wnuls contract
        String[][] argsApprove = new String[][]{new String[]{wAsset.toString()}, new String[]{v.toString()}};
        String rApprove = wNull.callWithReturnValue("approve", null, argsApprove, BigInteger.ZERO);*/

       // require(new Boolean(rApprove), "NulswapV1: Approve did not succeeded!");
        //Utils.emit(new DebugEvent("clinitTest log3-3", "JJ "+v+" "+ Msg.sender()+ getTokenBalance(Msg.address(), wAsset)));
        String[][] args = new String[][]{new String[]{v.toString()}, new String[]{Msg.sender().toString()}};
        String rWithdraw = wAsset.callWithReturnValue("withdraw", null, args, BigInteger.ZERO);
        require(new Boolean(rWithdraw), "NulswapV1: Withdraw did not succeed");
    }

    /**
     * Get MultiAsset Wrapped Contract
     *
     * @param chainId
     * @param assetId
     * */
    public Address getMUltiwAsset(@Required int chainId, @Required int assetId ) {
      //  require(contractCanDeploy || !Msg.sender().isContract(), "NulswapV1: contract can not call this function");
        require(chainId >= 0 && assetId >= 0, "NulswapV1: Invalid chain id or Invalid assetId");

        if(_wAssets.get(chainId) == null || _wAssets.get(chainId).get(assetId) == null){
            // Utils.emit(new DebugEvent("clinitTest log114", assetId + ", " + ", " + ", "));

            String _asset =  Utils.deploy(new String[]{ "wasset", "wS"+BigInteger.valueOf(Block.timestamp()).toString()}, wAssetCopy, new String[]{"wAsset", "wAsset", "8", String.valueOf(chainId), String.valueOf(assetId)});
            Map<Integer, Address> a = _wAssets.get(chainId);
            if (a == null) {
                a = new HashMap<>();
                _wAssets.put(chainId, a);
            }
            a.put(assetId, new Address(_asset));

        }
        //Utils.emit(new DebugEvent("clinitTest log411", assetId + ", " + ", " + ", "));

        return _wAssets.get(chainId).get(assetId);
    }

    public void setWAsset(int[] chainIds, int[] assetIds, String[] wAssets){
        onlyOwner();
        require(chainIds.length == assetIds.length && assetIds.length == wAssets.length, "array length error");
        for (int i = 0, length = chainIds.length; i < length; i++) {
            int chainId = chainIds[i];
            int assetId = assetIds[i];
            String wAsset = wAssets[i];
            Map<Integer, Address> map = _wAssets.get(chainId);
            if (map == null) {
                map = new HashMap<>();
                _wAssets.put(chainId, map);
            }
            map.put(assetId, new Address(wAsset));
        }
    }

    /**
     * Blacklist Address
     *
     * @param user
     * */
    public void blacklistAddress(Address user){
        onlyOwner();
        blacklist.put(user, true);
    }

    /**
     * Unblacklist Address
     *
     * @param user
     * */
    public void unBlacklistAddress(Address user){
        onlyOwner();
        blacklist.put(user, false);
    }

    /**
     * Set new treasury
     *
     * @param newTreasury New treasury address
     * */
    public void setTreasury(Address newTreasury){
        onlyOwner();
        treasury = newTreasury;
    }

    /**
     * Set new referral fee
     *
     * @param newRefFee
     * */
    public void setRefFee(BigInteger newRefFee){
        onlyOwner();
        require(newRefFee.compareTo(platformFee) < 0, "NulswapRouterV3: Referral fee must be lower than platform");
        refFee = newRefFee;
    }

    /**
     * Set new platform fee
     *
     * */
    public void setPlatformFee(BigInteger newPlatformFee){
        onlyOwner();
        require(newPlatformFee.compareTo(refFee) > 0 && newPlatformFee.compareTo(BASIS_POINTS) < 0, "NulswapRouterV3: Platform fee must be higher than platform Fee");
        platformFee = newPlatformFee;
    }

    /**
     * Pause Router
     */
    public void pause(){
        onlyOwner();
       paused = true;
    }

    /**
     * Unpause Router
     */
    public void unpause(){
        onlyOwner();
        paused = false;
    }

    /**
     * Recover Lost Nuls in Router
     */
    public void recoverLostNuls(){
        onlyOwner();
        Msg.sender().transfer(Msg.address().balance());
    }

    /**
     * Recover Lost MultiAssets in Router
     *
     * @param chainId Asset Chain Id
     * @param assetId Asset Id
     */
    public void recoverLostWAssets(Integer chainId, Integer assetId){
        onlyOwner();
        Msg.sender().transfer(Msg.address().balance(), chainId, assetId);
    }

    /**
     * Recover Lost Tokens in Router
     *
     * @param token_ Token Contract Address
     */
    public void recoverLostTokens(Address token_){
        onlyOwner();
        safeTransfer(token_, Msg.sender(), safeBalanceOf(token_, Msg.address()));
    }




}