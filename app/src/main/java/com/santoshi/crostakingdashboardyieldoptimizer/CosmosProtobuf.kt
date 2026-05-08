package com.santoshi.crostakingdashboardyieldoptimizer

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class Coin(@ProtoNumber(1) val denom: String, @ProtoNumber(2) val amount: String)

@Serializable
data class MsgDelegate(
    @ProtoNumber(1) val delegatorAddress: String,
    @ProtoNumber(2) val validatorAddress: String,
    @ProtoNumber(3) val amount: Coin
)

// VERIFIABLE FIX: The exact Cosmos SDK Bank module specification
@kotlinx.serialization.Serializable
data class MsgSend(
    @kotlinx.serialization.protobuf.ProtoNumber(1) val from_address: String,
    @kotlinx.serialization.protobuf.ProtoNumber(2) val to_address: String,
    @kotlinx.serialization.protobuf.ProtoNumber(3) val amount: List<Coin>
)

@Serializable
data class MsgWithdrawDelegationReward(
    @ProtoNumber(1) val delegatorAddress: String,
    @ProtoNumber(2) val validatorAddress: String
)

// VERIFIABLE FIX: Protobuf Blueprint for Redelegation
@kotlinx.serialization.Serializable
data class MsgBeginRedelegate(
    @kotlinx.serialization.protobuf.ProtoNumber(1) val delegator_address: String,
    @kotlinx.serialization.protobuf.ProtoNumber(2) val validator_src_address: String,
    @kotlinx.serialization.protobuf.ProtoNumber(3) val validator_dst_address: String,
    @kotlinx.serialization.protobuf.ProtoNumber(4) val amount: Coin
)

@kotlinx.serialization.Serializable
data class GenericAuthorization(
    @kotlinx.serialization.protobuf.ProtoNumber(1) val msg: String
)

@kotlinx.serialization.Serializable
data class Timestamp(
    @kotlinx.serialization.protobuf.ProtoNumber(1) val seconds: Long,
    @kotlinx.serialization.protobuf.ProtoNumber(2) val nanos: Int = 0
)

@kotlinx.serialization.Serializable
data class Grant(
    @kotlinx.serialization.protobuf.ProtoNumber(1) val authorization: ProtoAny,
    @kotlinx.serialization.protobuf.ProtoNumber(2) val expiration: Timestamp?
)

@kotlinx.serialization.Serializable
data class MsgGrant(
    @kotlinx.serialization.protobuf.ProtoNumber(1) val granter: String,
    @kotlinx.serialization.protobuf.ProtoNumber(2) val grantee: String,
    @kotlinx.serialization.protobuf.ProtoNumber(3) val grant: Grant
)

@kotlinx.serialization.Serializable
data class MsgRevoke(
    @kotlinx.serialization.protobuf.ProtoNumber(1) val granter: String,
    @kotlinx.serialization.protobuf.ProtoNumber(2) val grantee: String,
    @kotlinx.serialization.protobuf.ProtoNumber(3) val msgTypeUrl: String
)

@Serializable
data class ProtoAny(
    @ProtoNumber(1) val typeUrl: String,
    @ProtoNumber(2) val value: ByteArray
)

@Serializable
data class TxBody(
    @ProtoNumber(1) val messages: List<ProtoAny>,
    @ProtoNumber(2) val memo: String
)

@Serializable
data class PubKeySecp256k1(@ProtoNumber(1) val key: ByteArray)

@Serializable
data class ModeInfoSingle(@ProtoNumber(1) val mode: Int)

@Serializable
data class ModeInfo(@ProtoNumber(1) val single: ModeInfoSingle)

@Serializable
data class SignerInfo(
    @ProtoNumber(1) val publicKey: ProtoAny,
    @ProtoNumber(2) val modeInfo: ModeInfo,
    @ProtoNumber(3) val sequence: Long
)

@Serializable
data class Fee(
    @ProtoNumber(1) val amount: List<Coin>,
    @ProtoNumber(2) val gasLimit: Long,
    @ProtoNumber(3) val payer: String = "",
    @ProtoNumber(4) val granter: String = ""
)

@Serializable
data class AuthInfo(
    @ProtoNumber(1) val signerInfos: List<SignerInfo>,
    @ProtoNumber(2) val fee: Fee
)

@Serializable
data class TxRaw(
    @ProtoNumber(1) val bodyBytes: ByteArray,
    @ProtoNumber(2) val authInfoBytes: ByteArray,
    @ProtoNumber(3) val signatures: List<ByteArray>
)